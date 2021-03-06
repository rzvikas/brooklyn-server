/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.brooklyn.location.jclouds;

import static org.jclouds.compute.options.RunScriptOptions.Builder.overrideLoginCredentials;
import static org.jclouds.compute.util.ComputeServiceUtils.execHttpResponse;
import static org.jclouds.scriptbuilder.domain.Statements.appendFile;
import static org.jclouds.scriptbuilder.domain.Statements.exec;
import static org.jclouds.scriptbuilder.domain.Statements.interpret;
import static org.jclouds.scriptbuilder.domain.Statements.newStatementList;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;

import org.apache.brooklyn.core.config.Sanitizer;
import org.apache.brooklyn.core.location.LocationConfigKeys;
import org.apache.brooklyn.core.location.cloud.CloudLocationConfig;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.apache.brooklyn.util.net.Networking;
import org.apache.brooklyn.util.net.Protocol;
import org.apache.brooklyn.util.net.ReachableSocketFinder;
import org.apache.brooklyn.util.ssh.BashCommands;
import org.apache.brooklyn.util.ssh.IptablesCommands;
import org.apache.brooklyn.util.ssh.IptablesCommands.Chain;
import org.apache.brooklyn.util.ssh.IptablesCommands.Policy;
import org.apache.brooklyn.util.time.Duration;
import org.jclouds.Constants;
import org.jclouds.ContextBuilder;
import org.jclouds.aws.ec2.AWSEC2Api;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.ComputeServiceContext;
import org.jclouds.compute.RunScriptOnNodesException;
import org.jclouds.compute.domain.ExecResponse;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.compute.domain.OperatingSystem;
import org.jclouds.compute.options.RunScriptOptions;
import org.jclouds.compute.predicates.OperatingSystemPredicates;
import org.jclouds.docker.DockerApi;
import org.jclouds.docker.domain.Container;
import org.jclouds.domain.LoginCredentials;
import org.jclouds.ec2.compute.domain.PasswordDataAndPrivateKey;
import org.jclouds.ec2.compute.functions.WindowsLoginCredentialsFromEncryptedData;
import org.jclouds.ec2.domain.PasswordData;
import org.jclouds.ec2.features.WindowsApi;
import org.jclouds.encryption.bouncycastle.config.BouncyCastleCryptoModule;
import org.jclouds.logging.slf4j.config.SLF4JLoggingModule;
import org.jclouds.scriptbuilder.domain.Statement;
import org.jclouds.scriptbuilder.domain.Statements;
import org.jclouds.sshj.config.SshjSshClientModule;
import org.jclouds.util.Predicates2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.Beta;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.io.Files;
import com.google.common.net.HostAndPort;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.inject.Module;

public class JcloudsUtil {

    // TODO Review what utility methods are needed, and what is now supported in jclouds 1.1

    private static final Logger LOG = LoggerFactory.getLogger(JcloudsUtil.class);

    private JcloudsUtil() {}

    /**
     * @deprecated since 0.7; see {@link BashCommands}
     */
    @Deprecated
    public static String APT_INSTALL = "apt-get install -f -y -qq --force-yes";

    /**
     * @deprecated since 0.7; see {@link BashCommands}
     */
    @Deprecated
    public static String installAfterUpdatingIfNotPresent(String cmd) {
       String aptInstallCmd = APT_INSTALL + " " + cmd;
       return String.format("which %s || (%s || (apt-get update && %s))", cmd, aptInstallCmd, aptInstallCmd);
    }

    /**
     * @deprecated since 0.7
     */
    @Deprecated
    public static Predicate<NodeMetadata> predicateMatchingById(final NodeMetadata node) {
        return predicateMatchingById(node.getId());
    }

    /**
     * @deprecated since 0.7
     */
    @Deprecated
    public static Predicate<NodeMetadata> predicateMatchingById(final String id) {
        Predicate<NodeMetadata> nodePredicate = new Predicate<NodeMetadata>() {
            @Override public boolean apply(NodeMetadata arg0) {
                return id.equals(arg0.getId());
            }
            @Override public String toString() {
                return "node.id=="+id;
            }
        };
        return nodePredicate;
    }

    /**
     * @deprecated since 0.7; see {@link IptablesCommands}
     */
    @Deprecated
    public static Statement authorizePortInIpTables(int port) {
        // TODO gogrid rules only allow ports 22, 3389, 80 and 443.
        // the first rule will be ignored, so we have to apply this
        // directly
        return Statements.newStatementList(// just in case iptables are being used, try to open 8080
                exec("iptables -I INPUT 1 -p tcp --dport " + port + " -j ACCEPT"),//
                exec("iptables -I RH-Firewall-1-INPUT 1 -p tcp --dport " + port + " -j ACCEPT"),//
                exec("iptables-save"));
    }

    /**
     * @throws RunScriptOnNodesException
     * @throws IllegalStateException     If do not find exactly one matching node
     *
     * @deprecated since 0.7
     */
    @Deprecated
    public static ExecResponse runScriptOnNode(ComputeService computeService, NodeMetadata node, Statement statement, String scriptName) throws RunScriptOnNodesException {
        // TODO Includes workaround for NodeMetadata's equals/hashcode method being wrong.

        Map<? extends NodeMetadata, ExecResponse> scriptResults = computeService.runScriptOnNodesMatching(
                JcloudsUtil.predicateMatchingById(node),
                statement,
                new RunScriptOptions().nameTask(scriptName));
        if (scriptResults.isEmpty()) {
            throw new IllegalStateException("No matching node found when executing script "+scriptName+": expected="+node);
        } else if (scriptResults.size() > 1) {
            throw new IllegalStateException("Multiple nodes matched predicate: id="+node.getId()+"; expected="+node+"; actual="+scriptResults.keySet());
        } else {
            return Iterables.getOnlyElement(scriptResults.values());
        }
    }

    /**
     * @deprecated since 0.7; see {@link #installJavaAndCurl(OperatingSystem)}
     */
    @Deprecated
    public static final Statement APT_RUN_SCRIPT = newStatementList(//
          exec(installAfterUpdatingIfNotPresent("curl")),//
          exec("(which java && java -fullversion 2>&1|egrep -q 1.6 ) ||"),//
          execHttpResponse(URI.create("http://whirr.s3.amazonaws.com/0.2.0-incubating-SNAPSHOT/sun/java/install")),//
          exec(new StringBuilder()//
                .append("echo nameserver 208.67.222.222 >> /etc/resolv.conf\n")//
                // jeos hasn't enough room!
                .append("rm -rf /var/cache/apt /usr/lib/vmware-tools\n")//
                .append("echo \"export PATH=\\\"$JAVA_HOME/bin/:$PATH\\\"\" >> /root/.bashrc")//
                .toString()));

    /**
     * @deprecated since 0.7; see {@link #installJavaAndCurl(OperatingSystem)}
     */
    @Deprecated
    public static final Statement YUM_RUN_SCRIPT = newStatementList(
          exec("which curl ||yum --nogpgcheck -y install curl"),//
          exec("(which java && java -fullversion 2>&1|egrep -q 1.6 ) ||"),//
          execHttpResponse(URI.create("http://whirr.s3.amazonaws.com/0.2.0-incubating-SNAPSHOT/sun/java/install")),//
          exec(new StringBuilder()//
                .append("echo nameserver 208.67.222.222 >> /etc/resolv.conf\n") //
                .append("echo \"export PATH=\\\"$JAVA_HOME/bin/:$PATH\\\"\" >> /root/.bashrc")//
                .toString()));

    /**
     * @deprecated since 0.7; {@link #installJavaAndCurl(OperatingSystem)}
     */
    @Deprecated
    public static final Statement ZYPPER_RUN_SCRIPT = exec(new StringBuilder()//
          .append("echo nameserver 208.67.222.222 >> /etc/resolv.conf\n")//
          .append("which curl || zypper install curl\n")//
          .append("(which java && java -fullversion 2>&1|egrep -q 1.6 ) || zypper install java-1.6.0-openjdk\n")//
          .toString());

    // Code taken from RunScriptData
    /**
     * @deprecated since 0.7; see {@link BashCommands#installJava7()} and {@link BashCommands#INSTALL_CURL}
     */
    @Deprecated
    public static Statement installJavaAndCurl(OperatingSystem os) {
       if (os == null || OperatingSystemPredicates.supportsApt().apply(os))
          return APT_RUN_SCRIPT;
       else if (OperatingSystemPredicates.supportsYum().apply(os))
          return YUM_RUN_SCRIPT;
       else if (OperatingSystemPredicates.supportsZypper().apply(os))
          return ZYPPER_RUN_SCRIPT;
       else
          throw new IllegalArgumentException("don't know how to handle" + os.toString());
    }

    /**
     * @deprecated since 0.7; see {@link ComputeServiceRegistry#findComputeService(ConfigBag, boolean)}
     */
    @Deprecated
    public static ComputeService findComputeService(ConfigBag conf) {
        return ComputeServiceRegistryImpl.INSTANCE.findComputeService(conf, true);
    }

    /**
     * @deprecated since 0.7; see {@link ComputeServiceRegistry#findComputeService(ConfigBag, boolean)}
     */
    @Deprecated
    public static ComputeService findComputeService(ConfigBag conf, boolean allowReuse) {
        return ComputeServiceRegistryImpl.INSTANCE.findComputeService(conf, allowReuse);
    }

    /**
     * Returns the jclouds modules we typically install
     *
     * @deprecated since 0.7; see {@link ComputeServiceRegistry}
     */
    @Deprecated
    public static ImmutableSet<Module> getCommonModules() {
        return ImmutableSet.<Module> of(
                new SshjSshClientModule(),
                new SLF4JLoggingModule(),
                new BouncyCastleCryptoModule());
    }

    /**
     *  Temporary constructor to address https://issues.apache.org/jira/browse/JCLOUDS-615.
     *  <p>
     *  See https://issues.apache.org/jira/browse/BROOKLYN-6 .
     *  When https://issues.apache.org/jira/browse/JCLOUDS-615 is fixed in the jclouds we use,
     *  we can remove the useSoftlayerFix argument.
     *  <p>
     *  (Marked Beta as that argument will likely be removed.)
     *
     *  @since 0.7.0
     *  @deprecated since 0.11.0; instead use BlobStoreContextFactoryImpl.INSTANCE
     */
    @Beta
    @Deprecated
    public static BlobStoreContext newBlobstoreContext(String provider, @Nullable String endpoint, String identity, String credential) {
        ConfigBag conf = ConfigBag.newInstance();
        conf.put(LocationConfigKeys.CLOUD_PROVIDER, provider);
        conf.put(LocationConfigKeys.ACCESS_IDENTITY, identity);
        conf.put(LocationConfigKeys.ACCESS_CREDENTIAL, credential);
        conf.put(CloudLocationConfig.CLOUD_ENDPOINT, endpoint);

        return BlobStoreContextFactoryImpl.INSTANCE.newBlobStoreContext(conf);
    }

    /**
     * @deprecated since 0.7
     */
    @Deprecated
    protected static String getDeprecatedProperty(ConfigBag conf, String key) {
        if (conf.containsKey(key)) {
            LOG.warn("Jclouds using deprecated brooklyn-jclouds property "+key+": "+Sanitizer.sanitize(conf.getAllConfig()));
            return (String) conf.getStringKey(key);
        } else {
            return null;
        }
    }

    /**
     * @deprecated since 0.7
     */
    @Deprecated
    // Do this so that if there's a problem with our USERNAME's ssh key, we can still get in to check
    // TODO Once we're really confident there are not going to be regular problems, then delete this
    public static Statement addAuthorizedKeysToRoot(File publicKeyFile) throws IOException {
        String publicKey = Files.toString(publicKeyFile, Charsets.UTF_8);
        return addAuthorizedKeysToRoot(publicKey);
    }

    /**
     * @deprecated since 0.7
     */
    @Deprecated
    public static Statement addAuthorizedKeysToRoot(String publicKey) {
        return newStatementList(
                appendFile("/root/.ssh/authorized_keys", Splitter.on('\n').split(publicKey)),
                interpret("chmod 600 /root/.ssh/authorized_keys"));
    }

    /**
     * @deprecated since 0.9.0; use {@link #getFirstReachableAddress(NodeMetadata, Duration)}
     */
    @Deprecated
    public static String getFirstReachableAddress(ComputeServiceContext context, NodeMetadata node) {
        // Previously this called jclouds `sshForNode().apply(Node)` to check all IPs of node (private+public),
        // to find one that is reachable. It does `openSocketFinder.findOpenSocketOnNode(node, node.getLoginPort(), ...)`.
        // This keeps trying for time org.jclouds.compute.reference.ComputeServiceConstants.Timeouts.portOpen.
        // TODO Want to configure this timeout here.
        //
        // TODO We could perhaps instead just set `templateOptions.blockOnPort(loginPort, 120)`, but need
        // to be careful to only set that if config WAIT_FOR_SSHABLE is true. For some advanced networking examples
        // (e.g. using DNAT on CloudStack), the brooklyn machine won't be able to reach the VM until some additional
        // setup steps have been done. See links from Andrea:
        //     https://github.com/jclouds/jclouds/pull/895
        //     https://issues.apache.org/jira/browse/WHIRR-420
        //     jclouds.ssh.max-retries
        //     jclouds.ssh.retry-auth
        //
        // With `sshForNode`, we'd seen exceptions:
        //     java.lang.IllegalStateException: Optional.get() cannot be called on an absent value
        //     from org.jclouds.crypto.ASN1Codec.createASN1Sequence(ASN1Codec.java:86), if the ssh key has a passphrase, against AWS.
        // And others reported:
        //     java.lang.IllegalArgumentException: DER length more than 4 bytes
        //     when using a key with a passphrase (perhaps from other clouds?); not sure if that's this callpath or a different one.

        return getFirstReachableAddress(node, Duration.FIVE_MINUTES);
    }
    
    /**
     * Uses {@link Networking#isReachablePredicate()} to determine reachability.
     * @see #getReachableAddresses(NodeMetadata, Duration, Predicate)
     */
    public static String getFirstReachableAddress(NodeMetadata node, Duration timeout) {
        return getFirstReachableAddress(node, timeout, Networking.isReachablePredicate());
    }

    /** @see #getReachableAddresses(NodeMetadata, Duration, Predicate) */
    public static String getFirstReachableAddress(NodeMetadata node, Duration timeout, Predicate<? super HostAndPort> socketTester) {
        Iterable<HostAndPort> addresses = getReachableAddresses(node, timeout, socketTester);
        HostAndPort address = Iterables.getFirst(addresses, null);
        if (address != null) {
            return address.getHostText();
        } else {
            throw new IllegalStateException("No reachable IPs for " + node + "; check whether the node is " +
                    "reachable and whether it meets the requirements of the HostAndPort tester: " + socketTester);
        }
    }

    /**
     * @return The public and private addresses of node that are reachable.
     * @see #getReachableAddresses(Iterable, Duration, Predicate)
     */
    public static Iterable<HostAndPort> getReachableAddresses(NodeMetadata node, Duration timeout, Predicate<? super HostAndPort> socketTester) {
        final int port = node.getLoginPort();
        return getReachableAddresses(Iterables.concat(node.getPublicAddresses(), node.getPrivateAddresses()), port, timeout, socketTester);
    }

    /** @see #getReachableAddresses(Iterable, Duration, Predicate) */
    public static Iterable<HostAndPort> getReachableAddresses(Iterable<String> hosts, final int port, Duration timeout, Predicate<? super HostAndPort> socketTester) {
        FluentIterable<HostAndPort> sockets = FluentIterable
                .from(hosts)
                .transform(new Function<String, HostAndPort>() {
                        @Override public HostAndPort apply(String input) {
                            return HostAndPort.fromParts(input, port);
                        }});
        return getReachableAddresses(sockets, timeout, socketTester);
    }

    /**
     * Uses {@link ReachableSocketFinder} to determine which sockets are reachable. Iterators
     * are unmodifiable and are lazily evaluated.
     * @param sockets The host-and-ports to test
     * @param timeout Max time to try to connect to the ip:port
     * @param socketTester A predicate determining reachability.
     */
    public static Iterable<HostAndPort> getReachableAddresses(Iterable<HostAndPort> sockets, Duration timeout, Predicate<? super HostAndPort> socketTester) {
        ReachableSocketFinder finder = new ReachableSocketFinder(socketTester);
        return finder.findOpenSocketsOnNode(sockets, timeout);
    }

    // Suggest at least 15 minutes for timeout
    public static String waitForPasswordOnAws(ComputeService computeService, final NodeMetadata node, long timeout, TimeUnit timeUnit) throws TimeoutException {
        ComputeServiceContext computeServiceContext = computeService.getContext();
        AWSEC2Api ec2Client = computeServiceContext.unwrapApi(AWSEC2Api.class);
        final WindowsApi client = ec2Client.getWindowsApi().get();
        final String region = node.getLocation().getParent().getId();

        // The Administrator password will take some time before it is ready - Amazon says sometimes 15 minutes.
        // So we create a predicate that tests if the password is ready, and wrap it in a retryable predicate.
        Predicate<String> passwordReady = new Predicate<String>() {
            @Override public boolean apply(String s) {
                if (Strings.isNullOrEmpty(s)) return false;
                PasswordData data = client.getPasswordDataInRegion(region, s);
                if (data == null) return false;
                return !Strings.isNullOrEmpty(data.getPasswordData());
            }
        };

        LOG.info("Waiting for password, for "+node.getProviderId()+":"+node.getId());
        Predicate<String> passwordReadyRetryable = Predicates2.retry(passwordReady, timeUnit.toMillis(timeout), 10*1000, TimeUnit.MILLISECONDS);
        boolean ready = passwordReadyRetryable.apply(node.getProviderId());
        if (!ready) throw new TimeoutException("Password not available for "+node+" in region "+region+" after "+timeout+" "+timeUnit.name());

        // Now pull together Amazon's encrypted password blob, and the private key that jclouds generated
        PasswordDataAndPrivateKey dataAndKey = new PasswordDataAndPrivateKey(
                client.getPasswordDataInRegion(region, node.getProviderId()),
                node.getCredentials().getPrivateKey());

        // And apply it to the decryption function
        WindowsLoginCredentialsFromEncryptedData f = computeServiceContext.utils().injector().getInstance(WindowsLoginCredentialsFromEncryptedData.class);
        LoginCredentials credentials = f.apply(dataAndKey);

        return credentials.getPassword();
    }

    public static Map<Integer, Integer> dockerPortMappingsFor(JcloudsLocation docker, String containerId) {
        ComputeServiceContext context = null;
        try {
            Properties properties = new Properties();
            properties.setProperty(Constants.PROPERTY_TRUST_ALL_CERTS, Boolean.toString(true));
            properties.setProperty(Constants.PROPERTY_RELAX_HOSTNAME, Boolean.toString(true));
            context = ContextBuilder.newBuilder("docker")
                    .endpoint(docker.getEndpoint())
                    .credentials(docker.getIdentity(), docker.getCredential())
                    .overrides(properties)
                    .modules(ImmutableSet.<Module>of(new SLF4JLoggingModule(), new SshjSshClientModule()))
                    .build(ComputeServiceContext.class);
            DockerApi api = context.unwrapApi(DockerApi.class);
            Container container = api.getContainerApi().inspectContainer(containerId);
            Map<Integer, Integer> portMappings = Maps.newLinkedHashMap();
            Map<String, List<Map<String, String>>> ports = container.networkSettings().ports();
            if (ports == null) ports = ImmutableMap.<String, List<Map<String,String>>>of();

            LOG.debug("Docker will forward these ports {}", ports);
            for (Map.Entry<String, List<Map<String, String>>> entrySet : ports.entrySet()) {
                String containerPort = Iterables.get(Splitter.on("/").split(entrySet.getKey()), 0);
                String hostPort = Iterables.getOnlyElement(Iterables.transform(entrySet.getValue(),
                        new Function<Map<String, String>, String>() {
                            @Override
                            public String apply(Map<String, String> hostIpAndPort) {
                                return hostIpAndPort.get("HostPort");
                            }
                        }));
                portMappings.put(Integer.parseInt(containerPort), Integer.parseInt(hostPort));
            }
            return portMappings;
        } finally {
            if (context != null) {
                context.close();
            }
        }
    }

    /**
     * @deprecated since 0.7
     */
    @Deprecated
    public static void mapSecurityGroupRuleToIpTables(ComputeService computeService, NodeMetadata node,
            LoginCredentials credentials, String networkInterface, Iterable<Integer> ports) {
        for (Integer port : ports) {
            String insertIptableRule = IptablesCommands.insertIptablesRule(Chain.INPUT, networkInterface,
                    Protocol.TCP, port, Policy.ACCEPT);
            Statement statement = Statements.newStatementList(exec(insertIptableRule));
            ExecResponse response = computeService.runScriptOnNode(node.getId(), statement,
                    overrideLoginCredentials(credentials).runAsRoot(false));
            if (response.getExitStatus() != 0) {
                String msg = String.format("Cannot insert the iptables rule for port %d. Error: %s", port,
                        response.getError());
                LOG.error(msg);
                throw new RuntimeException(msg);
            }
        }
    }

}
