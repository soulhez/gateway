/**
 * Copyright (c) 2007-2014 Kaazing Corporation. All rights reserved.
 *
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

package org.kaazing.gateway.transport.ssl;

import org.kaazing.gateway.server.test.GatewayRule;
import org.kaazing.gateway.server.test.config.GatewayConfiguration;
import org.kaazing.gateway.server.test.config.builder.GatewayConfigurationBuilder;
import org.kaazing.robot.junit.annotation.Robotic;
import org.kaazing.robot.junit.rules.RobotRule;
import org.apache.log4j.BasicConfigurator;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.URI;
import java.security.KeyStore;
import java.security.Security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.junit.rules.RuleChain.outerRule;

public class OcspIT {

    private static final char[] password = "ab987c".toCharArray();
    private static final KeyStore trustStore;        // democa certificate
    static {
        // Enable JSSE provider's OCSP support
        System.setProperty("com.sun.net.ssl.checkRevocation", "true");
        System.setProperty("com.sun.security.enableCRLDP", "true");
        Security.setProperty("ocsp.enable", "true");

        BasicConfigurator.configure();

        try {
            // Initialize TrustStore (democa certificate) of gateway
            trustStore = KeyStore.getInstance("JKS");
            FileInputStream tis = new FileInputStream("target/truststore/truststore.db");
            trustStore.load(tis, null);
            tis.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private final RobotRule robot = new RobotRule();

    private final GatewayRule gateway = new GatewayRule() {
        {
            KeyStore keyStore;
            try {
                // Initialize KeyStore of gateway
                keyStore = KeyStore.getInstance("JCEKS");
                FileInputStream kis = new FileInputStream("target/truststore/keystore.db");
                keyStore.load(kis, password);
                kis.close();
            } catch(Exception e) {
                throw new RuntimeException(e);
            }

            // @formatter:off
            GatewayConfiguration configuration =
                    new GatewayConfigurationBuilder()
                            .service()
                                .accept(URI.create("ssl://localhost:9558"))
                                .type("echo")
                                .acceptOption("ssl.verifyClient", "required")
                            .done()
                            .security()
                                .keyStore(keyStore)
                                .keyStorePassword(password)
                                .trustStore(trustStore)
                            .done()
                            .done();
            // @formatter:on
            init(configuration);
        }
    };

    @Rule
    public final TestRule chain = outerRule(robot).around(gateway);

    /*
     * client <---> Gateway(localhost:9558) <---> robot/ocsp responder (localhost:8192)
     * OCSP responder would say that the client certificate is good, hence the SSL handshake
     * goes through
     */
    @Robotic(script = "ocspGoodCertificate")
    @Test(timeout = 17000)
    public void testGoodCertificate() throws Exception {
        KeyStore clientStore = KeyStore.getInstance("JCEKS");
        InputStream cis = getClass().getClassLoader().getResourceAsStream("ocsp.db");
        clientStore.load(cis, password);
        cis.close();

        // Configure client socket factory
        //     - with its client certificate
        //     - to trust gateway's certificate
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(clientStore, password);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        SSLSocketFactory clientSocketFactory = sslContext.getSocketFactory();

        Socket socket = null;
        try {
            socket = clientSocketFactory.createSocket("localhost", 9558);
            BufferedWriter w = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            BufferedReader r = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String expected = "Hello World!";
            w.write(expected);
            w.newLine();
            w.flush();
            String got = r.readLine();
            assertEquals(expected, got);
            w.close();
            r.close();
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ioe) {
                    //noop
                }
            }
        }

        robot.join();
    }


    /*
     * client <---> Gateway(localhost:9558) <---> robot/ocsp responder (localhost:8192)
     * OCSP responder would say that the client certificate is revoked, hence the failed
     * SSL handshake.
     */
    @Robotic(script = "ocspRevokedCertificate")
    @Test(timeout = 17000)
    public void testRevokedCertificate() throws Exception {
        KeyStore clientStore = KeyStore.getInstance("JCEKS");
        InputStream cis = getClass().getClassLoader().getResourceAsStream("ocsp.db");
        clientStore.load(cis, password);
        cis.close();

        // Configure client socket factory
        //     - with its client certificate
        //     - to trust gateway's certificate
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(clientStore, password);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        SSLSocketFactory clientSocketFactory = sslContext.getSocketFactory();

        SSLSocket socket = (SSLSocket)clientSocketFactory.createSocket("localhost", 9558);
        try {
            socket.startHandshake();
            fail("Shouldn't establish SSL connection since certificate is revoked(as per OCSP responder)");
        } catch(IOException expected) {
            // noop - expected exception
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ioe) {
                    //noop
                }
            }
        }

        robot.join();
    }

}
