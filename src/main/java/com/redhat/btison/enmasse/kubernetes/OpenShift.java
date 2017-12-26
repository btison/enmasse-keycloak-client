package com.redhat.btison.enmasse.kubernetes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.redhat.btison.enmasse.Endpoint;
import com.redhat.btison.enmasse.TestUtils;

import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.client.OpenShiftClient;

public class OpenShift extends Kubernetes {

    private static Logger log = LoggerFactory.getLogger(OpenShift.class);

    public OpenShift(ClusterAccess clusterAccess) {
        super(clusterAccess.createOpenShiftClient(), clusterAccess.getNamespace());
    }

    public Endpoint getRestEndpoint() {
        OpenShiftClient openShift = client.adapt(OpenShiftClient.class);
        Route route = openShift.routes().inNamespace(globalNamespace).withName("restapi").get();
        Endpoint endpoint = new Endpoint(route.getSpec().getHost(), 443);
        if (TestUtils.resolvable(endpoint)) {
            return endpoint;
        } else {
            log.info("Endpoint didn't resolve, falling back to service endpoint");
            return getEndpoint(globalNamespace, "address-controller", "https");
        }
    }

    public Endpoint getKeycloakEndpoint() {
        OpenShiftClient openShift = client.adapt(OpenShiftClient.class);
        Route route = openShift.routes().inNamespace(globalNamespace).withName("keycloak").get();
        Endpoint endpoint = new Endpoint(route.getSpec().getHost(), 443);
        log.info("Testing endpoint : " + endpoint);
        if (TestUtils.resolvable(endpoint)) {
            return endpoint;
        } else {
            log.info("Endpoint didn't resolve, falling back to service endpoint");
            return getEndpoint(globalNamespace, "standard-authservice", "https");
        }
    }

    @Override
    public Endpoint getExternalEndpoint(String namespace, String endpointName) {
        OpenShiftClient openShift = client.adapt(OpenShiftClient.class);
        Route route = openShift.routes().inNamespace(namespace).withName(endpointName).get();
        Endpoint endpoint = new Endpoint(route.getSpec().getHost(), 443);
        log.info("Testing endpoint : " + endpoint);
        if (TestUtils.resolvable(endpoint)) {
            return endpoint;
        } else {
            log.info("Endpoint didn't resolve, falling back to service endpoint");
            return getEndpoint(namespace, endpointName, "https");
        }
    }

}
