/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.model;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecurityContext;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.rbac.RoleBinding;
import io.fabric8.kubernetes.api.model.rbac.RoleRef;
import io.fabric8.kubernetes.api.model.rbac.RoleRefBuilder;
import io.fabric8.kubernetes.api.model.rbac.Subject;
import io.fabric8.kubernetes.api.model.rbac.SubjectBuilder;
import io.strimzi.api.kafka.model.CertificateAuthority;
import io.strimzi.api.kafka.model.ContainerEnvVar;
import io.strimzi.api.kafka.model.EntityOperatorSpec;
import io.strimzi.api.kafka.model.EntityUserOperatorSpec;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.api.kafka.model.Probe;
import io.strimzi.api.kafka.model.ProbeBuilder;
import io.strimzi.operator.cluster.ClusterOperatorConfig;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.model.OrderedProperties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;

/**
 * Represents the User Operator deployment
 */
public class EntityUserOperator extends AbstractModel {
    protected static final String APPLICATION_NAME = "entity-user-operator";
    
    protected static final String USER_OPERATOR_CONTAINER_NAME = "user-operator";
    private static final String NAME_SUFFIX = "-entity-user-operator";
    protected static final String METRICS_AND_LOG_CONFIG_SUFFIX = NAME_SUFFIX + "-config";
    private static final String CERT_SECRET_KEY_NAME = "entity-operator";

    // Port configuration
    protected static final int HEALTHCHECK_PORT = 8081;
    protected static final String HEALTHCHECK_PORT_NAME = "healthcheck";

    // User Operator configuration keys
    public static final String ENV_VAR_RESOURCE_LABELS = "STRIMZI_LABELS";
    public static final String ENV_VAR_KAFKA_BOOTSTRAP_SERVERS = "STRIMZI_KAFKA_BOOTSTRAP_SERVERS";
    public static final String ENV_VAR_WATCHED_NAMESPACE = "STRIMZI_NAMESPACE";
    public static final String ENV_VAR_FULL_RECONCILIATION_INTERVAL_MS = "STRIMZI_FULL_RECONCILIATION_INTERVAL_MS";
    public static final String ENV_VAR_CLIENTS_CA_CERT_SECRET_NAME = "STRIMZI_CA_CERT_NAME";
    public static final String ENV_VAR_CLIENTS_CA_KEY_SECRET_NAME = "STRIMZI_CA_KEY_NAME";
    public static final String ENV_VAR_CLIENTS_CA_NAMESPACE = "STRIMZI_CA_NAMESPACE";
    public static final String ENV_VAR_CLIENTS_CA_VALIDITY = "STRIMZI_CA_VALIDITY";
    public static final String ENV_VAR_CLIENTS_CA_RENEWAL = "STRIMZI_CA_RENEWAL";
    public static final String ENV_VAR_CLUSTER_CA_CERT_SECRET_NAME = "STRIMZI_CLUSTER_CA_CERT_SECRET_NAME";
    public static final String ENV_VAR_EO_KEY_SECRET_NAME = "STRIMZI_EO_KEY_SECRET_NAME";
    public static final String ENV_VAR_SECRET_PREFIX = "STRIMZI_SECRET_PREFIX";
    public static final String ENV_VAR_ACLS_ADMIN_API_SUPPORTED = "STRIMZI_ACLS_ADMIN_API_SUPPORTED";
    public static final String ENV_VAR_MAINTENANCE_TIME_WINDOWS = "STRIMZI_MAINTENANCE_TIME_WINDOWS";
    public static final Probe DEFAULT_HEALTHCHECK_OPTIONS = new ProbeBuilder().withTimeoutSeconds(EntityUserOperatorSpec.DEFAULT_HEALTHCHECK_TIMEOUT)
            .withInitialDelaySeconds(EntityUserOperatorSpec.DEFAULT_HEALTHCHECK_DELAY).build();

    // Volume name of the temporary volume used by the UO container
    // Because the container shares the pod with other containers, it needs to have unique name
    /*test*/ static final String USER_OPERATOR_TMP_DIRECTORY_DEFAULT_VOLUME_NAME = "strimzi-uo-tmp";

    private String kafkaBootstrapServers;
    private String watchedNamespace;
    private String resourceLabels;
    private String secretPrefix;
    private long reconciliationIntervalMs;
    private int clientsCaValidityDays;
    private int clientsCaRenewalDays;
    protected List<ContainerEnvVar> templateContainerEnvVars;
    protected SecurityContext templateContainerSecurityContext;
    private boolean aclsAdminApiSupported = false;
    private List<String> maintenanceWindows;

    /**
     * @param reconciliation   The reconciliation
     * @param resource Kubernetes resource with metadata containing the namespace and cluster name
     */
    protected EntityUserOperator(Reconciliation reconciliation, HasMetadata resource) {
        super(reconciliation, resource, APPLICATION_NAME);
        this.name = userOperatorName(cluster);
        this.readinessPath = "/";
        this.livenessProbeOptions = DEFAULT_HEALTHCHECK_OPTIONS;
        this.livenessPath = "/";
        this.readinessProbeOptions = DEFAULT_HEALTHCHECK_OPTIONS;

        // create a default configuration
        this.kafkaBootstrapServers = defaultBootstrapServers(cluster);
        this.watchedNamespace = namespace;
        this.reconciliationIntervalMs = EntityUserOperatorSpec.DEFAULT_FULL_RECONCILIATION_INTERVAL_SECONDS * 1_000;
        this.secretPrefix = EntityUserOperatorSpec.DEFAULT_SECRET_PREFIX;
        this.resourceLabels = ModelUtils.defaultResourceLabels(cluster);

        this.ancillaryConfigMapName = metricAndLogConfigsName(cluster);
        this.logAndMetricsConfigVolumeName = "entity-user-operator-metrics-and-logging";
        this.logAndMetricsConfigMountPath = "/opt/user-operator/custom-config/";
        this.clientsCaValidityDays = CertificateAuthority.DEFAULT_CERTS_VALIDITY_DAYS;
        this.clientsCaRenewalDays = CertificateAuthority.DEFAULT_CERTS_RENEWAL_DAYS;
    }

    public void setWatchedNamespace(String watchedNamespace) {
        this.watchedNamespace = watchedNamespace;
    }

    public String getWatchedNamespace() {
        return watchedNamespace;
    }

    public void setReconciliationIntervalMs(long reconciliationIntervalMs) {
        this.reconciliationIntervalMs = reconciliationIntervalMs;
    }

    public long getReconciliationIntervalMs() {
        return reconciliationIntervalMs;
    }

    public void setClientsCaValidityDays(int clientsCaValidityDays) {
        this.clientsCaValidityDays = clientsCaValidityDays;
    }

    public long getClientsCaValidityDays() {
        return this.clientsCaValidityDays;
    }

    public void setClientsCaRenewalDays(int clientsCaRenewalDays) {
        this.clientsCaRenewalDays = clientsCaRenewalDays;
    }

    public long getClientsCaRenewalDays() {
        return this.clientsCaRenewalDays;
    }

    public void setKafkaBootstrapServers(String kafkaBootstrapServers) {
        this.kafkaBootstrapServers = kafkaBootstrapServers;
    }

    public String getKafkaBootstrapServers() {
        return kafkaBootstrapServers;
    }

    public String getSecretPrefix() {
        return secretPrefix;
    }

    public static String secretName(String cluster) {
        return KafkaResources.entityUserOperatorSecretName(cluster);
    }

    public void setSecretPrefix(String secretPrefix) {
        this.secretPrefix = secretPrefix;
    }

    protected static String defaultBootstrapServers(String cluster) {
        return KafkaResources.bootstrapServiceName(cluster) + ":" + EntityUserOperatorSpec.DEFAULT_BOOTSTRAP_SERVERS_PORT;
    }

    public static String userOperatorName(String cluster) {
        return cluster + NAME_SUFFIX;
    }

    public static String metricAndLogConfigsName(String cluster) {
        return cluster + METRICS_AND_LOG_CONFIG_SUFFIX;
    }

    /**
     * Get the name of the UO role binding given the name of the {@code cluster}.
     * @param cluster The cluster name.
     * @return The name of the role binding.
     */
    public static String roleBindingForRoleName(String cluster) {
        return cluster + "-entity-user-operator-role";
    }

    @Override
    protected String getDefaultLogConfigFileName() {
        return "entityUserOperatorDefaultLoggingProperties";
    }

    @Override
    public String getAncillaryConfigMapKeyLogConfig() {
        return "log4j2.properties";
    }

    /**
     * Create an Entity User Operator from given desired resource
     *
     * @param reconciliation The reconciliation
     * @param kafkaAssembly desired resource with cluster configuration containing the Entity User Operator one
     * @return Entity User Operator instance, null if not configured in the ConfigMap
     */
    public static EntityUserOperator fromCrd(Reconciliation reconciliation, Kafka kafkaAssembly) {
        EntityUserOperator result = null;
        EntityOperatorSpec entityOperatorSpec = kafkaAssembly.getSpec().getEntityOperator();
        if (entityOperatorSpec != null) {

            EntityUserOperatorSpec userOperatorSpec = entityOperatorSpec.getUserOperator();
            if (userOperatorSpec != null) {

                String namespace = kafkaAssembly.getMetadata().getNamespace();
                result = new EntityUserOperator(reconciliation, kafkaAssembly);

                result.setOwnerReference(kafkaAssembly);
                String image = userOperatorSpec.getImage();
                if (image == null) {
                    image = System.getenv().getOrDefault(ClusterOperatorConfig.STRIMZI_DEFAULT_USER_OPERATOR_IMAGE, "quay.io/strimzi/operator:latest");
                }
                result.setImage(image);
                result.setWatchedNamespace(userOperatorSpec.getWatchedNamespace() != null ? userOperatorSpec.getWatchedNamespace() : namespace);
                result.setReconciliationIntervalMs(userOperatorSpec.getReconciliationIntervalSeconds() * 1_000);
                result.setLogging(userOperatorSpec.getLogging());
                result.setGcLoggingEnabled(userOperatorSpec.getJvmOptions() == null ? DEFAULT_JVM_GC_LOGGING_ENABLED : userOperatorSpec.getJvmOptions().isGcLoggingEnabled());
                result.setSecretPrefix(userOperatorSpec.getSecretPrefix() == null ? EntityUserOperatorSpec.DEFAULT_SECRET_PREFIX : userOperatorSpec.getSecretPrefix());
                result.setJvmOptions(userOperatorSpec.getJvmOptions());
                result.setResources(userOperatorSpec.getResources());
                if (userOperatorSpec.getReadinessProbe() != null) {
                    result.setReadinessProbe(userOperatorSpec.getReadinessProbe());
                }
                if (userOperatorSpec.getLivenessProbe() != null) {
                    result.setLivenessProbe(userOperatorSpec.getLivenessProbe());
                }

                if (kafkaAssembly.getSpec().getClientsCa() != null) {
                    if (kafkaAssembly.getSpec().getClientsCa().getValidityDays() > 0) {
                        result.setClientsCaValidityDays(kafkaAssembly.getSpec().getClientsCa().getValidityDays());
                    }

                    if (kafkaAssembly.getSpec().getClientsCa().getRenewalDays() > 0) {
                        result.setClientsCaRenewalDays(kafkaAssembly.getSpec().getClientsCa().getRenewalDays());
                    }
                }

                if (kafkaAssembly.getSpec().getKafka().getAuthorization() != null) {
                    // Indicates whether the Kafka Admin API for ACL management are supported by the configured authorizer
                    // plugin. This information is passed to the User Operator.
                    result.aclsAdminApiSupported = kafkaAssembly.getSpec().getKafka().getAuthorization().supportsAdminApi();
                }

                if (kafkaAssembly.getSpec().getMaintenanceTimeWindows() != null)    {
                    result.maintenanceWindows = kafkaAssembly.getSpec().getMaintenanceTimeWindows();
                }
            }
        }
        return result;
    }

    @Override
    protected List<Container> getContainers(ImagePullPolicy imagePullPolicy) {

        return singletonList(new ContainerBuilder()
                .withName(USER_OPERATOR_CONTAINER_NAME)
                .withImage(getImage())
                .withArgs("/opt/strimzi/bin/user_operator_run.sh")
                .withEnv(getEnvVars())
                .withPorts(singletonList(createContainerPort(HEALTHCHECK_PORT_NAME, HEALTHCHECK_PORT, "TCP")))
                .withLivenessProbe(ProbeGenerator.httpProbe(livenessProbeOptions, livenessPath + "healthy", HEALTHCHECK_PORT_NAME))
                .withReadinessProbe(ProbeGenerator.httpProbe(readinessProbeOptions, readinessPath + "ready", HEALTHCHECK_PORT_NAME))
                .withResources(getResources())
                .withVolumeMounts(getVolumeMounts())
                .withImagePullPolicy(determineImagePullPolicy(imagePullPolicy, getImage()))
                .withSecurityContext(templateContainerSecurityContext)
                .build());
    }

    @Override
    protected List<EnvVar> getEnvVars() {
        List<EnvVar> varList = new ArrayList<>();
        varList.add(buildEnvVar(ENV_VAR_KAFKA_BOOTSTRAP_SERVERS, kafkaBootstrapServers));
        varList.add(buildEnvVar(ENV_VAR_WATCHED_NAMESPACE, watchedNamespace));
        varList.add(buildEnvVar(ENV_VAR_RESOURCE_LABELS, resourceLabels));
        varList.add(buildEnvVar(ENV_VAR_FULL_RECONCILIATION_INTERVAL_MS, Long.toString(reconciliationIntervalMs)));
        varList.add(buildEnvVar(ENV_VAR_CLIENTS_CA_KEY_SECRET_NAME, KafkaResources.clientsCaKeySecretName(cluster)));
        varList.add(buildEnvVar(ENV_VAR_CLIENTS_CA_CERT_SECRET_NAME, KafkaResources.clientsCaCertificateSecretName(cluster)));
        varList.add(buildEnvVar(ENV_VAR_CLIENTS_CA_NAMESPACE, namespace));
        varList.add(buildEnvVar(ENV_VAR_CLIENTS_CA_VALIDITY, Integer.toString(clientsCaValidityDays)));
        varList.add(buildEnvVar(ENV_VAR_CLIENTS_CA_RENEWAL, Integer.toString(clientsCaRenewalDays)));
        varList.add(buildEnvVar(ENV_VAR_CLUSTER_CA_CERT_SECRET_NAME, KafkaCluster.clusterCaCertSecretName(cluster)));
        varList.add(buildEnvVar(ENV_VAR_EO_KEY_SECRET_NAME, EntityUserOperator.secretName(cluster)));
        varList.add(buildEnvVar(ENV_VAR_STRIMZI_GC_LOG_ENABLED, String.valueOf(gcLoggingEnabled)));
        varList.add(buildEnvVar(ENV_VAR_SECRET_PREFIX, secretPrefix));
        varList.add(buildEnvVar(ENV_VAR_ACLS_ADMIN_API_SUPPORTED, String.valueOf(aclsAdminApiSupported)));
        ModelUtils.javaOptions(varList, getJvmOptions());

        // Add shared environment variables used for all containers
        varList.addAll(getRequiredEnvVars());

        addContainerEnvsToExistingEnvs(varList, templateContainerEnvVars);

        // if maintenance time windows are set, we pass them as environment variable
        if (maintenanceWindows != null && !maintenanceWindows.isEmpty())    {
            // The Cron expressions can contain commas -> we use semi-colon as delimiter
            varList.add(buildEnvVar(ENV_VAR_MAINTENANCE_TIME_WINDOWS, String.join(";", maintenanceWindows)));
        }

        return varList;
    }

    public List<Volume> getVolumes() {
        return asList(VolumeUtils.createConfigMapVolume(logAndMetricsConfigVolumeName, ancillaryConfigMapName));
    }

    private List<VolumeMount> getVolumeMounts() {
        return asList(createTempDirVolumeMount(USER_OPERATOR_TMP_DIRECTORY_DEFAULT_VOLUME_NAME),
                VolumeUtils.createVolumeMount(logAndMetricsConfigVolumeName, logAndMetricsConfigMountPath),
                VolumeUtils.createVolumeMount(EntityOperator.EUO_CERTS_VOLUME_NAME, EntityOperator.EUO_CERTS_VOLUME_MOUNT),
                VolumeUtils.createVolumeMount(EntityOperator.TLS_SIDECAR_CA_CERTS_VOLUME_NAME, EntityOperator.TLS_SIDECAR_CA_CERTS_VOLUME_MOUNT));
    }

    @Override
    protected String getRoleName() {
        return EntityOperator.getRoleName(cluster);
    }

    public RoleBinding generateRoleBindingForRole(String namespace, String watchedNamespace) {
        Subject ks = new SubjectBuilder()
                .withKind("ServiceAccount")
                .withName(EntityOperator.entityOperatorServiceAccountName(cluster))
                .withNamespace(namespace)
                .build();

        RoleRef roleRef = new RoleRefBuilder()
                .withName(getRoleName())
                .withApiGroup("rbac.authorization.k8s.io")
                .withKind("Role")
                .build();

        RoleBinding rb = generateRoleBinding(
                roleBindingForRoleName(cluster),
                watchedNamespace,
                roleRef,
                singletonList(ks)
        );

        // We set OwnerReference only within the same namespace since it does not work cross-namespace
        if (!namespace.equals(watchedNamespace)) {
            rb.getMetadata().setOwnerReferences(Collections.emptyList());
        }

        return rb;
    }

    public void setContainerEnvVars(List<ContainerEnvVar> envVars) {
        templateContainerEnvVars = envVars;
    }

    public void setContainerSecurityContext(SecurityContext securityContext) {
        templateContainerSecurityContext = securityContext;
    }

    /**
     * Transforms properties to log4j2 properties file format and adds property for reloading the config
     * @param properties map with properties
     * @return modified string with monitorInterval
     */
    @Override
    public String createLog4jProperties(OrderedProperties properties) {
        if (!properties.asMap().keySet().contains("monitorInterval")) {
            properties.addPair("monitorInterval", "30");
        }
        return super.createLog4jProperties(properties);
    }

    /**
     * Generate the Secret containing the Entity User Operator certificate signed by the cluster CA certificate used for TLS based
     * internal communication with Kafka and Zookeeper.
     * It also contains the related Entity User Operator private key.
     *
     * @param clusterCa The cluster CA.
     * @param isMaintenanceTimeWindowsSatisfied Indicates whether we are in the maintenance window or not.
     *                                          This is used for certificate renewals
     * @return The generated Secret.
     */
    public Secret generateSecret(ClusterCa clusterCa, boolean isMaintenanceTimeWindowsSatisfied) {
        Secret secret = clusterCa.entityUserOperatorSecret();
        return ModelUtils.buildSecret(reconciliation, clusterCa, secret, namespace, EntityUserOperator.secretName(cluster), name, 
            CERT_SECRET_KEY_NAME, labels, createOwnerReference(), isMaintenanceTimeWindowsSatisfied);
    }
}
