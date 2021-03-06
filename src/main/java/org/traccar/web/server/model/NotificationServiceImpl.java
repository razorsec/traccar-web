/*
 * Copyright 2015 Vitaly Litvak (vitavaque@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.web.server.model;

import com.google.gson.stream.JsonWriter;
import com.google.gwt.user.server.rpc.RemoteServiceServlet;
import com.google.inject.persist.Transactional;
import org.traccar.web.client.model.NotificationService;
import org.traccar.web.shared.model.*;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.mail.*;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.persistence.EntityManager;
import javax.servlet.ServletException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

@Singleton
public class NotificationServiceImpl extends RemoteServiceServlet implements NotificationService {
    @Inject
    private Provider<User> sessionUser;

    @Inject
    private Provider<EntityManager> entityManager;

    @Inject
    protected Logger logger;

    static class DeviceEvents implements Comparator<DeviceEvent> {
        Set<DeviceEvent> offlineEvents;
        Set<DeviceEvent> geoFenceEvents;

        void addEvent(DeviceEvent deviceEvent) {
            switch (deviceEvent.getType()) {
                case OFFLINE:
                    if (offlineEvents == null) {
                        offlineEvents = new HashSet<DeviceEvent>();
                    }
                    offlineEvents.add(deviceEvent);
                    break;
                case GEO_FENCE_ENTER:
                case GEO_FENCE_EXIT:
                    if (geoFenceEvents == null) {
                        geoFenceEvents = new HashSet<DeviceEvent>();
                    }
                    geoFenceEvents.add(deviceEvent);
                    break;
            }
        }

        @Override
        public int compare(DeviceEvent o1, DeviceEvent o2) {
            int r = o1.getDevice().getName().compareTo(o2.getDevice().getName());
            if (r == 0) {
                if (o1.getType() == DeviceEventType.GEO_FENCE_ENTER || o1.getType() == DeviceEventType.GEO_FENCE_EXIT) {
                    return o1.getPosition().getTime().compareTo(o2.getPosition().getTime());
                }
                return o1.getTime().compareTo(o2.getTime());
            }
            return r;
        }

        List<DeviceEvent> offlineEvents() {
            return sorted(offlineEvents);
        }

        List<DeviceEvent> geoFenceEvents() {
            return sorted(geoFenceEvents);
        }

        List<DeviceEvent> sorted(Set<DeviceEvent> unsorted) {
            if (unsorted == null) {
                return Collections.emptyList();
            }
            List<DeviceEvent> result = new ArrayList<DeviceEvent>(unsorted);
            Collections.sort(result, this);
            return result;
        }

        void markAsSent() {
            markAsSent(offlineEvents);
            markAsSent(geoFenceEvents);
        }

        void markAsSent(Set<DeviceEvent> events) {
            if (events != null) {
                for (DeviceEvent event : events) {
                    event.setNotificationSent(true);
                }
            }
        }
    }

    public static class NotificationSender extends ScheduledTask {
        @Inject
        Provider<EntityManager> entityManager;

        @Transactional
        @Override
        public void doWork() throws Exception {
            Set<DeviceEventType> eventTypes = new HashSet<DeviceEventType>();
            for (User user : entityManager.get().createQuery("SELECT u FROM User u INNER JOIN FETCH u.notificationEvents", User.class).getResultList()) {
                eventTypes.addAll(user.getNotificationEvents());
            }

            if (eventTypes.isEmpty()) {
                return;
            }

            Map<User, DeviceEvents> events = new HashMap<User, DeviceEvents>();
            List<User> admins = null;
            Map<User, List<User>> managers = new HashMap<User, List<User>>();

            for (DeviceEvent event : entityManager.get().createQuery("SELECT e FROM DeviceEvent e INNER JOIN FETCH e.position WHERE e.notificationSent = :false AND e.type IN (:types)", DeviceEvent.class)
                                    .setParameter("false", false)
                                    .setParameter("types", eventTypes)
                                    .getResultList()) {
                Device device = event.getDevice();

                for (User user : device.getUsers()) {
                    addEvent(events, user, event);
                    List<User> userManagers = managers.get(user);
                    if (userManagers == null) {
                        userManagers = new LinkedList<User>();
                        User manager = user.getManagedBy();
                        while (manager != null) {
                            if (!manager.getNotificationEvents().isEmpty()) {
                                userManagers.add(manager);
                            }
                            manager = manager.getManagedBy();
                        }
                        if (userManagers.isEmpty()) {
                            userManagers = Collections.emptyList();
                        }
                        managers.put(user, userManagers);
                    }
                    for (User manager : userManagers) {
                        addEvent(events, manager, event);
                    }
                }

                if (admins == null) {
                    admins = entityManager.get().createQuery("SELECT u FROM User u WHERE u.admin=:true", User.class)
                            .setParameter("true", true)
                            .getResultList();
                }

                for (User admin : admins) {
                    addEvent(events, admin, event);
                }
            }

            for (Map.Entry<User, DeviceEvents> entry : events.entrySet()) {
                User user = entry.getKey();
                if (user.getEmail() == null || user.getEmail().trim().isEmpty()) {
                    logger.warning("User '" + user.getLogin() + "' has empty email field");
                    continue;
                }

                NotificationSettings settings = findNotificationSettings(user);
                if (settings == null) {
                    logger.warning("Unable to find notification settings for '" + user.getLogin() + "' (id=" + user.getId() + "), thus he won't receive any notifications.");
                    continue;
                }

                DeviceEvents deviceEvents = entry.getValue();

                StringBuilder message = new StringBuilder();
                if (appendOfflineEventsText(message, deviceEvents.offlineEvents())) {
                    message.append("\n\n");
                }
                appendGeoFenceText(message, deviceEvents.geoFenceEvents());

                boolean sentEmail = sendEmail(settings, user, "[traccar-web] Notification", message.toString());
                boolean sentPushbullet = sendPushbullet(settings, user, "[traccar-web] Notification", message.toString());
                if (sentPushbullet || sentEmail) {
                    deviceEvents.markAsSent();
                }
            }
        }

        private void addEvent(Map<User, DeviceEvents> events, User user, DeviceEvent event) {
            // check whether user wants to receive such notification events
            if (!user.getNotificationEvents().contains(event.getType())) {
                return;
            }
            if (event.getType() == DeviceEventType.GEO_FENCE_ENTER || event.getType() == DeviceEventType.GEO_FENCE_EXIT) {
                // check whether user has access to the geo-fence
                if (!user.getAdmin() && !user.hasAccessTo(event.getGeoFence())) {
                    return;
                }
            }

            DeviceEvents userEvents = events.get(user);
            if (userEvents == null) {
                userEvents = new DeviceEvents();
                events.put(user, userEvents);
            }
            userEvents.addEvent(event);
        }

        private NotificationSettings findNotificationSettings(User user) {
            NotificationSettings s = getNotificationSettings(user);

            // lookup settings in manager hierarchy
            if (s == null) {
                User manager = user.getManagedBy();
                while (s == null && manager != null) {
                    s = getNotificationSettings(manager);
                    manager = manager.getManagedBy();
                }
            }

            // take settings from first admin ordered by id
            if (s == null) {
                List<NotificationSettings> settings = entityManager.get().createQuery("SELECT s FROM NotificationSettings s WHERE s.user.admin=:true ORDER BY s.user.id ASC", NotificationSettings.class)
                        .setParameter("true", true)
                        .getResultList();
                if (!settings.isEmpty()) {
                    s = settings.get(0);
                }
            }

            return s;
        }

        private NotificationSettings getNotificationSettings(User user) {
            List<NotificationSettings> settings = entityManager.get().createQuery("SELECT n FROM NotificationSettings n WHERE n.user = :user", NotificationSettings.class)
                    .setParameter("user", user)
                    .getResultList();
            return settings.isEmpty() ? null : settings.get(0);
        }

        private boolean appendOfflineEventsText(StringBuilder msg, List<DeviceEvent> events) {
            if (events.size() == 1) {
                DeviceEvent event = events.get(0);
                msg.append("Device '").append(event.getDevice().getName()).append("' went offline at ").append(event.getTime());
            } else if (events.size() > 1) {
                msg.append("Following devices went offline:\n");
                for (DeviceEvent event : events) {
                    msg.append("\n  '").append(event.getDevice().getName()).append("' (").append(event.getDevice().getUniqueId()).append(") at ").append(event.getTime());
                }
            }
            return !events.isEmpty();
        }

        private boolean appendGeoFenceText(StringBuilder msg, List<DeviceEvent> events) {
            for (DeviceEvent event : events) {
                msg.append("Device '").append(event.getDevice().getName()).append("' ")
                        .append(event.getType() == DeviceEventType.GEO_FENCE_ENTER ? "entered" : "exited")
                        .append(" geo-fence '").append(event.getGeoFence().getName()).append("' at ").append(event.getPosition().getTime()).append('\n');
            }
            return !events.isEmpty();
        }

        private boolean sendEmail(NotificationSettings settings, User user, String subject, String body) {
            // perform some validation of e-mail settings
            if (settings.getServer() == null || settings.getServer().trim().isEmpty() ||
                settings.getFromAddress() == null || settings.getFromAddress().trim().isEmpty()) {
                return false;
            }

            logger.info("Sending Email notification to '" + user.getEmail() + "'...");

            Session session = getSession(settings);
            Message msg = new MimeMessage(session);
            Transport transport = null;
            try {
                msg.setFrom(new InternetAddress(settings.getFromAddress()));
                msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(user.getLogin() + " <" + user.getEmail() + ">", false));
                msg.setSubject(subject);

                msg.setText(body);
                msg.setHeader("X-Mailer", "traccar-web.sendmail");
                msg.setSentDate(new Date());

                transport = session.getTransport("smtp");
                transport.connect();
                transport.sendMessage(msg, msg.getAllRecipients());

                return true;
            } catch (MessagingException me) {
                logger.log(Level.SEVERE, "Unable to send Email message", me);
                return false;
            } finally {
                if (transport != null) try { transport.close(); } catch (MessagingException ignored) {}
            }
        }

        private boolean sendPushbullet(NotificationSettings settings, User user, String subject, String body) {
            // perform some validation of Pushbullet settings
            if (settings.getPushbulletAccessToken() == null || settings.getPushbulletAccessToken().trim().isEmpty()) {
                return false;
            }
            logger.info("Sending Pushbullet notification to '" + user.getEmail() + "'...");

            InputStream is = null;
            OutputStream os = null;
            try {
                URL url = new URL("https://api.pushbullet.com/v2/pushes");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", "Bearer " + settings.getPushbulletAccessToken());
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                os = conn.getOutputStream();
                JsonWriter writer = new JsonWriter(new OutputStreamWriter(os));
                writer.beginObject();
                writer
                    .name("email").value(user.getEmail())
                    .name("type").value("note")
                    .name("title").value(subject)
                        .name("body").value(body);
                writer.endObject();
                writer.flush();
                writer.close();
                is = conn.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(is));
                try {
                    String line;
                    logger.info("Pushbullet response: ");
                    while ((line = reader.readLine()) != null) logger.info(line);
                    return true;
                } finally {
                    reader.close();
                }
            } catch (MalformedURLException mue) {
                logger.log(Level.SEVERE, "Incorrect URL", mue);
                return false;
            } catch (IOException ioex) {
                logger.log(Level.SEVERE, "I/O Error", ioex);
                return false;
            } finally {
                if (is != null ) try { is.close(); } catch (IOException ignored) {}
            }
        }
    }

    @Inject
    private NotificationSender notificationSender;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    @Override
    public void init() throws ServletException {
        super.init();

        scheduler.scheduleAtFixedRate(notificationSender, 0, 1, TimeUnit.MINUTES);
    }

    @Transactional
    @RequireUser(roles = { Role.ADMIN, Role.MANAGER })
    @RequireWrite
    @Override
    public void checkEmailSettings(NotificationSettings settings) {
        // Validate smtp settings
        try {
            Session s = getSession(settings);
            Transport t = s.getTransport("smtp");
            t.connect();
            t.close();
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex);
        }
        // Validate 'From Address'
        try {
            new InternetAddress(settings.getFromAddress(), true);
        } catch (AddressException ae) {
            throw new IllegalArgumentException(ae);
        }
    }

    private static Session getSession(NotificationSettings settings) {
        final boolean DEBUG = false;
        Properties props = new Properties();

        props.put("mail.smtp.host", settings.getServer());
        props.put("mail.smtp.auth", Boolean.toString(settings.isUseAuthorization()));
        props.put("mail.debug", Boolean.toString(DEBUG));
        props.put("mail.smtp.port", Integer.toString(settings.getPort()));

        switch (settings.getSecureConnectionType()) {
            case SSL_TLS:
                props.put("mail.smtp.socketFactory.port", Integer.toString(settings.getPort()));
                props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
                props.put("mail.smtp.socketFactory.fallback", "false");
                props.put("mail.smtp.socketFactory.timeout", 10 * 1000);
                break;
            case STARTTLS:
                props.put("mail.smtp.starttls.required", "true");
                break;
        }

        final String userName = settings.getUsername();
        final String password = settings.getPassword();

        Authenticator authenticator = settings.isUseAuthorization() ? new Authenticator()
        {
            @Override
            protected PasswordAuthentication getPasswordAuthentication()
            {
                return new PasswordAuthentication(userName, password);
            }
        } : null;

        Session s = Session.getInstance(props, authenticator);
        s.setDebug(DEBUG);

        return s;
    }

    @Transactional
    @RequireUser(roles = { Role.ADMIN, Role.MANAGER })
    @RequireWrite
    @Override
    public void checkPushbulletSettings(NotificationSettings settings) {
        InputStream is = null;
        try {
            URL url = new URL("https://api.pushbullet.com/v2/users/me");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + settings.getPushbulletAccessToken());
            is = conn.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            try {
                String line;
                logger.info("Pushbullet response: ");
                while ((line = reader.readLine()) != null) logger.info(line);
            } finally {
                reader.close();
            }
        } catch (MalformedURLException mue) {
            throw new IllegalArgumentException(mue);
        } catch (IOException ioex) {
            throw new IllegalStateException(ioex);
        } finally {
            if (is != null ) try { is.close(); } catch (IOException ignored) {}
        }
    }

    @Transactional
    @RequireUser(roles = { Role.ADMIN, Role.MANAGER })
    @Override
    public NotificationSettings getSettings() {
        List<NotificationSettings> settings = entityManager.get().createQuery("SELECT n FROM NotificationSettings n WHERE n.user = :user", NotificationSettings.class)
                .setParameter("user", sessionUser.get())
                .getResultList();
        return settings.isEmpty() ? null : settings.get(0);
    }

    @Transactional
    @RequireUser(roles = { Role.ADMIN, Role.MANAGER })
    @RequireWrite
    @Override
    public void saveSettings(NotificationSettings settings) {
        NotificationSettings currentSettings = getSettings();
        if (currentSettings == null) {
            currentSettings = settings;
            settings.setUser(sessionUser.get());
        } else {
            currentSettings.copyFrom(settings);
        }
        entityManager.get().persist(currentSettings);
    }
}
