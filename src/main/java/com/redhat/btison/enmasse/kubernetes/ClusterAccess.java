package com.redhat.btison.enmasse.kubernetes;

import static io.fabric8.kubernetes.api.KubernetesHelper.DEFAULT_NAMESPACE;

import java.net.UnknownHostException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.api.KubernetesHelper;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.openshift.client.DefaultOpenShiftClient;
import io.fabric8.openshift.client.OpenShiftAPIGroups;
import io.fabric8.openshift.client.OpenShiftClient;
import io.fabric8.utils.Strings;

public class ClusterAccess {

    private static final Logger log = LoggerFactory.getLogger(ClusterAccess.class);

    private String namespace;

    public ClusterAccess(String namespace) {
        this.namespace = namespace;

        if (Strings.isNullOrBlank(this.namespace)) {
            this.namespace = KubernetesHelper.defaultNamespace();
        }
        if (Strings.isNullOrBlank(this.namespace)) {
            this.namespace = DEFAULT_NAMESPACE;
        }
    }

    public KubernetesClient createDefaultClient() {
        if (isOpenShift()) {
            return createOpenShiftClient();
        }

        return createKubernetesClient();
    }

    public KubernetesClient createKubernetesClient() {
        return new DefaultKubernetesClient(createDefaultConfig());
    }

    public OpenShiftClient createOpenShiftClient() {
        return new DefaultOpenShiftClient(createDefaultConfig());
    }

    private Config createDefaultConfig() {
        return new ConfigBuilder().withNamespace(getNamespace()).build();
    }

    public String getNamespace() {
        return namespace;
    }

    /**
     * Returns true if this cluster is a traditional OpenShift cluster with the <code>/oapi</code> REST API
     * or supports the new <code>/apis/image.openshift.io</code> API Group
     */
    public boolean isOpenShiftImageStream() {
        if (isOpenShift()) {
            OpenShiftClient openShiftClient = createOpenShiftClient();
            return openShiftClient.supportsOpenShiftAPIGroup(OpenShiftAPIGroups.IMAGE);
        }
        return false;
    }

    public boolean isOpenShift() {
        try {
            return KubernetesHelper.isOpenShift(createKubernetesClient());
        } catch (KubernetesClientException exp) {
            Throwable cause = exp.getCause();
            String prefix = cause instanceof UnknownHostException ? "Unknown host " : "";
            log.warn("Cannot access cluster for detecting mode: %s%s",
                     prefix,
                     cause != null ? cause.getMessage() : exp.getMessage());
            return false;
        }
    }

}
