package com.redhat.btison.enmasse;

import com.redhat.btison.enmasse.kubernetes.ClusterAccess;
import com.redhat.btison.enmasse.kubernetes.Kubernetes;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

public class KeycloakAdminClient {

    private static Logger log = LoggerFactory.getLogger(KeycloakAdminClient.class);

    private enum operations {CHECKREALM, CREATEUSER};

    @Option(name = "-h", usage = "Keycloak host", aliases = { "--host" })
    public String host;

    @Option(name = "-o", usage = "Operation", aliases = { "--operation" })
    public String operation;

    @Option(name = "--port", usage = "Keycloak port")
    public int port = 443;

    @Option(name = "-t", usage = "Timeout", aliases = { "--timeout" })
    public int timeout = 1;

    @Option(name = "-tu", usage = "TimeUnit for timeout", aliases = { "--timeunit" })
    public TimeUnit timeUnit = TimeUnit.MINUTES;

    @Option(name= "--admin-username", usage = "Keycloak admin user")
    public String adminUsername;

    @Option(name= "--admin-password", usage = "Keycloak admin password")
    public String adminPassword;

    @Option(name= "-u", usage = "Keycloak user", aliases = { "--username"})
    public String username;

    @Option(name= "-p", usage = "Keycloak password", aliases = { "--password"})
    public String password;

    @Option(name= "-r", usage = "Keycloak realm", aliases = { "--realm"})
    public String realm;

    @Option(name= "-n", usage = "OpenShift namespace", aliases = { "--namespace"})
    public String namespace;

    private Endpoint endpoint;

    private KeycloakCredentials credentials;

    private KeyStore trustStore;

    public static void main(String[] args) {

        KeycloakAdminClient client = new KeycloakAdminClient();
        CmdLineParser parser = new CmdLineParser(client);
        try {
            parser.parseArgument(args);
        } catch (CmdLineException e) {
            // handling of wrong arguments
            System.err.println(e.getMessage());
            parser.printUsage(System.err);
            System.exit(-1);
        }
        try {
            client.init();
            client.process();
            System.exit(0);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(-1);
        }
    }

    private void init() throws Exception {
        ClusterAccess clusterAccess = new ClusterAccess(namespace);
        Kubernetes kubernetes = Kubernetes.create(clusterAccess);
        if (host != null) {
            endpoint = new Endpoint(host, port);
        } else {
            endpoint = kubernetes.getKeycloakEndpoint();
        }
        if (adminUsername != null && adminPassword != null) {
            credentials = new KeycloakCredentials(adminUsername, adminPassword);
        } else {
            credentials = kubernetes.getKeycloakCredentials();
        }
        trustStore = createTrustStore(kubernetes.getKeycloakCA());
    }

    private void process() throws Exception {
        if (operations.CREATEUSER.name().equals(processOperation(operation))) {
            createUser(realm, username, password, timeout, timeUnit);
        } else if (operations.CHECKREALM.name().equals(processOperation(operation))) {
            checkRealmExists(realm, timeout, timeUnit);
        } else {
            throw new UnsupportedOperationException("Operation " + operation + " is not supported");
        }
    }

    private String processOperation(String operation) {
        return operation.replace("-", "").toUpperCase();
    }

    private void createUser(String realm, String userName, String password, int timeout, TimeUnit timeUnit) throws Exception {

        int maxRetries = 10;
        try (CloseableKeycloak keycloak = new CloseableKeycloak(endpoint, credentials, trustStore)) {
            RealmResource realmResource = checkRealmExists(keycloak.get(), realm, timeout, timeUnit);

            for (int retries = 0; retries < maxRetries; retries++) {
                try {
                    if (realmResource.users().search(userName).isEmpty()) {
                        UserRepresentation userRep = new UserRepresentation();
                        userRep.setUsername(userName);
                        CredentialRepresentation cred = new CredentialRepresentation();
                        cred.setType(CredentialRepresentation.PASSWORD);
                        cred.setValue(password);
                        cred.setTemporary(false);
                        userRep.setCredentials(Collections.singletonList(cred));
                        userRep.setEnabled(true);
                        Response response = keycloak.get().realm(realm).users().create(userRep);
                        if (response.getStatus() != 201) {
                            throw new RuntimeException("Unable to create user: " + response.getStatus());
                        }
                    } else {
                        log.info("User " + userName + " already created, skipping");
                    }
                    break;
                } catch (Exception e) {
                    log.info("Exception querying keycloak ({}), retrying", e.getMessage());
                    Thread.sleep(2000);
                }
            }
        }

        String sendGroup = "send_*";
        String receiveGroup = "recv_*";
        String manageGroup = "manage";
        createGroup(realm, sendGroup);
        createGroup(realm, receiveGroup);
        createGroup(realm, manageGroup);
        joinGroup(realm, sendGroup, userName, timeout, timeUnit);
        joinGroup(realm, receiveGroup, userName, timeout, timeUnit);
        joinGroup(realm, manageGroup, userName, timeout, timeUnit);
    }

    private void createGroup(String realm, String groupName) throws Exception {
        int maxRetries = 10;
        try (CloseableKeycloak keycloak = new CloseableKeycloak(endpoint, credentials, trustStore)) {
            if (!groupExist(keycloak, realm, groupName)) {
                for (int retries = 0; retries < maxRetries; retries++) {
                    try {
                        GroupRepresentation groupRep = new GroupRepresentation();
                        groupRep.setName(groupName);
                        Response response = keycloak.get().realm(realm).groups().add(groupRep);
                        if (response.getStatus() != 201) {
                            throw new RuntimeException("Unable to create group: " + response.getStatus());
                        }
                        break;
                    } catch (Exception e) {
                        log.info("Exception querying keycloak ({}), retrying", e.getMessage());
                        Thread.sleep(2000);
                    }
                }
            }
        }
    }

    private void joinGroup(String realm, String groupName, String username, int timeout, TimeUnit timeUnit) throws Exception {
        groupOperation(realm, groupName, username, timeout, timeUnit, (realmResource, clientId, groupId) -> {
            realmResource.users().get(clientId).joinGroup(groupId);
            log.info("User '{}' successfully joined group '{}'", username, groupName);
        });
    }

    private boolean groupExist(CloseableKeycloak keycloak, String realm, String groupName) {
        List<GroupRepresentation> groups =
                keycloak.get().realm(realm).groups()
                        .groups()
                        .stream()
                        .filter(group -> group.getName().equals(groupName))
                        .collect(Collectors.toList());
        return !groups.isEmpty();
    }

    private void groupOperation(String realm, String groupName, String username, int timeout, TimeUnit timeUnit,
                               GroupMethod<RealmResource, String, String> groupMethod) throws Exception {
        int maxRetries = 10;
        try (CloseableKeycloak keycloak = new CloseableKeycloak(endpoint, credentials, trustStore)) {
            RealmResource realmResource = checkRealmExists(keycloak.get(), realm, timeout, timeUnit);
            for (int retries = 0; retries < maxRetries; retries++) {
                try {
                    groupMethod.apply(
                            realmResource,
                            getClientId(keycloak, realm, username),
                            getGroupId(keycloak, realm, groupName));
                    break;
                } catch (Exception e) {
                    log.info("Exception querying keycloak ({}), retrying", e.getMessage());
                    Thread.sleep(2000);
                }
            }
        }
    }

    private String getClientId(CloseableKeycloak keycloak, String realm, String username) {
        List<UserRepresentation> users = keycloak.get().realm(realm).users().search(username);
        if (!users.isEmpty()) {
            return users.get(0).getId();
        }
        throw new RuntimeException("Unable to find user: " + username);
    }

    private String getGroupId(CloseableKeycloak keycloak, String realm, String groupName) {
        List<GroupRepresentation> groups =
                keycloak.get().realm(realm).groups()
                        .groups()
                        .stream()
                        .filter(group -> group.getName().equals(groupName))
                        .collect(Collectors.toList());
        if (!groups.isEmpty()) {
            return groups.get(0).getId();
        }
        throw new RuntimeException("Unable to find group: " + groupName);
    }

    private RealmResource checkRealmExists(String realmName, long timeout, TimeUnit timeUnit) throws Exception {
        try (CloseableKeycloak keycloak = new CloseableKeycloak(endpoint, credentials, trustStore)) {
            return checkRealmExists(keycloak.get(), realmName, timeout, timeUnit);
        }
    }

    private RealmResource checkRealmExists(Keycloak keycloak, String realmName, long timeout, TimeUnit timeUnit) throws Exception {
        long endTime = System.currentTimeMillis() + timeUnit.toMillis(timeout);
        RealmResource realmResource = null;
        while (System.currentTimeMillis() < endTime) {
            realmResource = getRealmResource(keycloak, realmName);
            if (realmResource != null) {
                return realmResource;
            }
            Thread.sleep(5000);
        }

        if (realmResource == null) {
            realmResource = getRealmResource(keycloak, realmName);
        }

        if (realmResource != null) {
            return realmResource;
        }

        throw new TimeoutException("Timed out waiting for realm " + realmName + " to exist");
    }

    private RealmResource getRealmResource(Keycloak keycloak, String realmName) throws Exception {
        return TestUtils.doRequestNTimes(10, () -> {
            List<RealmRepresentation> realms = keycloak.realms().findAll();
            for (RealmRepresentation realm : realms) {
                if (realm.getRealm().equals(realmName)) {
                    return keycloak.realm(realmName);
                }
            }
            return null;
        });
    }

    private KeyStore createTrustStore(String keycloakCaCert) throws Exception {
        try {
            KeyStore keyStore = KeyStore.getInstance("JKS");
            keyStore.load(null);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            keyStore.setCertificateEntry("standard-authservice",
                    cf.generateCertificate(new ByteArrayInputStream(keycloakCaCert.getBytes("UTF-8"))));

            return keyStore;
        } catch (Exception ignored) {
            log.warn("Error creating keystore for authservice CA", ignored);
            throw ignored;
        }
    }

    @FunctionalInterface
    interface GroupMethod<T, U, V> {
        void apply(T t, U u, V v);
    }

    private static class CloseableKeycloak implements AutoCloseable {

        private final Keycloak keycloak;

        private CloseableKeycloak(Endpoint endpoint, KeycloakCredentials credentials, KeyStore trustStore) {
            log.info("Logging into keycloak with {}/{}", credentials.getUsername(), credentials.getPassword());
            this.keycloak = KeycloakBuilder.builder()
                    .serverUrl("https://" + endpoint.getHost() + ":" + endpoint.getPort() + "/auth")
                    .realm("master")
                    .username(credentials.getUsername())
                    .password(credentials.getPassword())
                    .clientId("admin-cli")
                    .resteasyClient(new ResteasyClientBuilder()
                            .disableTrustManager()
                            .trustStore(trustStore)
                            .hostnameVerification(ResteasyClientBuilder.HostnameVerificationPolicy.ANY)
                            .build())
                    .build();
        }

        Keycloak get() {
            return keycloak;
        }

        @Override
        public void close() {
            keycloak.close();
        }
    }

}
