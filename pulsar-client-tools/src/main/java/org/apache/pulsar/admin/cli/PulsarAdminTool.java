/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.admin.cli;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;

import java.io.FileInputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

import org.apache.commons.lang3.StringUtils;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminBuilder;

public class PulsarAdminTool {
    protected final Map<String, Class<?>> commandMap;
    private final JCommander jcommander;
    protected final PulsarAdminBuilder adminBuilder;

    @Parameter(names = { "--admin-url" }, description = "Admin Service URL to which to connect.")
    String serviceUrl = null;

    @Parameter(names = { "--auth-plugin" }, description = "Authentication plugin class name.")
    String authPluginClassName = null;

    @Parameter(
        names = { "--auth-params" },
        description = "Authentication parameters, whose format is determined by the implementation " +
            "of method `configure` in authentication plugin class, for example \"key1:val1,key2:val2\" " +
            "or \"{\"key1\":\"val1\",\"key2\":\"val2\"}.")
    String authParams = null;

    @Parameter(names = { "--tls-allow-insecure" }, description = "Allow TLS insecure connection")
    Boolean tlsAllowInsecureConnection;
    
    @Parameter(names = { "--tls-trust-cert-path" }, description = "Allow TLS trust cert file path")
    String tlsTrustCertsFilePath;
    
    @Parameter(names = { "--tls-enable-hostname-verification" }, description = "Enable TLS common name verification")
    Boolean tlsEnableHostnameVerification;

    @Parameter(names = { "-h", "--help", }, help = true, description = "Show this help.")
    boolean help;

    PulsarAdminTool(Properties properties) throws Exception {
        // fallback to previous-version serviceUrl property to maintain backward-compatibility
        serviceUrl = StringUtils.isNotBlank(properties.getProperty("webServiceUrl"))
                ? properties.getProperty("webServiceUrl")
                : properties.getProperty("serviceUrl");
        authPluginClassName = properties.getProperty("authPlugin");
        authParams = properties.getProperty("authParams");
        boolean tlsAllowInsecureConnection = this.tlsAllowInsecureConnection != null ? this.tlsAllowInsecureConnection
                : Boolean.parseBoolean(properties.getProperty("tlsAllowInsecureConnection", "false"));

        boolean tlsEnableHostnameVerification = this.tlsEnableHostnameVerification != null
                ? this.tlsEnableHostnameVerification
                : Boolean.parseBoolean(properties.getProperty("tlsEnableHostnameVerification", "false"));
        final String tlsTrustCertsFilePath = StringUtils.isNotBlank(this.tlsTrustCertsFilePath)
                ? this.tlsTrustCertsFilePath
                : properties.getProperty("tlsTrustCertsFilePath");

        adminBuilder = PulsarAdmin.builder().allowTlsInsecureConnection(tlsAllowInsecureConnection)
                .enableTlsHostnameVerification(tlsEnableHostnameVerification)
                .tlsTrustCertsFilePath(tlsTrustCertsFilePath);

        jcommander = new JCommander();
        jcommander.setProgramName("pulsar-admin");
        jcommander.addObject(this);

        commandMap = new HashMap<>();
        commandMap.put("clusters", CmdClusters.class);
        commandMap.put("ns-isolation-policy", CmdNamespaceIsolationPolicy.class);
        commandMap.put("brokers", CmdBrokers.class);
        commandMap.put("broker-stats", CmdBrokerStats.class);
        commandMap.put("tenants", CmdTenants.class);
        commandMap.put("properties", CmdTenants.CmdProperties.class); // deprecated, doesn't show in usage()
        commandMap.put("namespaces", CmdNamespaces.class);
        commandMap.put("topics", CmdTopics.class);
        commandMap.put("schemas", CmdSchemas.class);
        commandMap.put("bookies", CmdBookies.class);

        // Hidden deprecated "persistent" and "non-persistent" subcommands
        commandMap.put("persistent", CmdPersistentTopics.class);
        commandMap.put("non-persistent", CmdNonPersistentTopics.class);


        commandMap.put("resource-quotas", CmdResourceQuotas.class);
        commandMap.put("functions", CmdFunctions.class);
        commandMap.put("functions-worker", CmdFunctionWorker.class);
        commandMap.put("source", CmdSources.class);
        commandMap.put("sink", CmdSinks.class);
    }

    private void setupCommands(Function<PulsarAdminBuilder, ? extends PulsarAdmin> adminFactory) {
        try {
            adminBuilder.serviceHttpUrl(serviceUrl);
            adminBuilder.authentication(authPluginClassName, authParams);
            PulsarAdmin admin = adminFactory.apply(adminBuilder);
            for (Map.Entry<String, Class<?>> c : commandMap.entrySet()) {
                if (admin != null) {
                    // Other mode, all components are initialized.
                    jcommander.addCommand(c.getKey(), c.getValue().getConstructor(PulsarAdmin.class).newInstance(admin));
                } else if (c.getKey().equals("functions") || c.getKey().equals("source") || c.getKey().equals("sink")) {
                    // In mode localrun, only some components are initialized, such as source, sink and functions
                    jcommander.addCommand(c.getKey(), c.getValue().getConstructor(PulsarAdmin.class).newInstance(admin));
                }
            }
        } catch (Exception e) {
            Throwable cause;
            if (e instanceof InvocationTargetException && null != e.getCause()) {
                cause = e.getCause();
            } else {
                cause = e;
            }
            System.err.println(cause.getClass() + ": " + cause.getMessage());
            System.exit(1);
        }
    }

    boolean run(String[] args) {
        return run(args, adminBuilder -> {
            try {
                return adminBuilder.build();
            } catch (Exception ex) {
                System.err.println(ex.getClass() + ": " + ex.getMessage());
                System.exit(1);
                return null;
            }
        });
    }

    boolean run(String[] args, Function<PulsarAdminBuilder, ? extends PulsarAdmin> adminFactory) {
        if (args.length == 0) {
            setupCommands(adminFactory);
            jcommander.usage();
            return false;
        }

        int cmdPos;
        for (cmdPos = 0; cmdPos < args.length; cmdPos++) {
            if (commandMap.containsKey(args[cmdPos])) {
                break;
            }
        }

        try {
            jcommander.parse(Arrays.copyOfRange(args, 0, Math.min(cmdPos, args.length)));
        } catch (Exception e) {
            System.err.println(e.getMessage());
            System.err.println();
            setupCommands(adminFactory);
            jcommander.usage();
            return false;
        }

        if (help) {
            setupCommands(adminFactory);
            jcommander.usage();
            return true;
        }

        if (cmdPos == args.length) {
            setupCommands(adminFactory);
            jcommander.usage();
            return false;
        } else {
            setupCommands(adminFactory);
            String cmd = args[cmdPos];
            JCommander obj = jcommander.getCommands().get(cmd);
            CmdBase cmdObj = (CmdBase) obj.getObjects().get(0);

            return cmdObj.run(Arrays.copyOfRange(args, cmdPos + 1, args.length));
        }
    }

    public static void main(String[] args) throws Exception {
        String configFile = args[0];
        Properties properties = new Properties();

        if (configFile != null) {
            try (FileInputStream fis = new FileInputStream(configFile)) {
                properties.load(fis);
            }
        }

        PulsarAdminTool tool = new PulsarAdminTool(properties);

        int cmdPos;
        for (cmdPos = 1; cmdPos < args.length; cmdPos++) {
            if (tool.commandMap.containsKey(args[cmdPos])) {
                break;
            }
        }

        ++cmdPos;
        boolean isLocalRun = cmdPos < args.length && "localrun".equals(args[cmdPos].toLowerCase());

        Function<PulsarAdminBuilder, ? extends PulsarAdmin> adminFactory;
        if (isLocalRun) {
            // bypass constructing admin client
            adminFactory = (adminBuilder) -> null;
        } else {
            adminFactory = (adminBuilder) -> {
                try {
                    return adminBuilder.build();
                } catch (Exception ex) {
                    System.err.println(ex.getClass() + ": " + ex.getMessage());
                    System.exit(1);
                    return null;
                }
            };
        }

        if (tool.run(Arrays.copyOfRange(args, 1, args.length), adminFactory)) {
            System.exit(0);
        } else {
            System.exit(1);
        }
    }
}
