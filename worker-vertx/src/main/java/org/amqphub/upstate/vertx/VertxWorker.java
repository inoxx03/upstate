/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.amqphub.upstate.vertx;

import io.vertx.core.Vertx;
import io.vertx.proton.ProtonClient;
import io.vertx.proton.ProtonConnection;
import io.vertx.proton.ProtonReceiver;
import io.vertx.proton.ProtonSender;
import java.util.HashMap;
import java.util.Map;
import org.apache.qpid.proton.amqp.messaging.AmqpValue;
import org.apache.qpid.proton.amqp.messaging.ApplicationProperties;
import org.apache.qpid.proton.amqp.messaging.Section;
import org.apache.qpid.proton.message.Message;

public class VertxWorker {
    private static String id = "worker-vertx-" +
        (Math.round(Math.random() * (10000 - 1000)) + 1000);

    public static void main(String[] args) {
        try {
            String host = args[0];
            int port = Integer.parseInt(args[1]);

            Vertx vertx = Vertx.vertx();
            ProtonClient client = ProtonClient.create(vertx);

            while (true) {
                client.connect(host, port, (res) -> {
                        if (res.failed()) {
                            res.cause().printStackTrace();
                            return;
                        }

                        ProtonConnection conn = res.result();
                        conn.setContainer(id);
                        conn.open();

                        handleRequests(vertx, conn);
                        sendStatusUpdates(vertx, conn);
                    });

                Thread.sleep(60 * 1000);
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void handleRequests(Vertx vertx, ProtonConnection conn) {
        ProtonReceiver receiver = conn.createReceiver("upstate/requests");
        ProtonSender sender = conn.createSender(null);

        receiver.handler((delivery, request) -> {
                String requestBody = (String) ((AmqpValue) request.getBody()).getValue();
                System.out.println("WORKER-VERTX: Received request '" + requestBody + "'");

                String responseBody;

                try {
                    responseBody = processRequest(request);
                } catch (Exception e) {
                    System.err.println("WORKER-VERTX: Failed processing message: " + e);
                    return;
                }

                System.out.println("WORKER-VERTX: Sending response '" + responseBody + "'");

                Map<String, String> props = new HashMap<String, String>();
                props.put("worker_id", conn.getContainer());

                Message response = Message.Factory.create();
                response.setAddress(request.getReplyTo());
                response.setCorrelationId(request.getMessageId());
                response.setBody(new AmqpValue(responseBody));
                response.setApplicationProperties(new ApplicationProperties(props));

                sender.send(response);
            });

        sender.open();
        receiver.open();
    }

    private static String processRequest(Message request) {
        String requestBody = (String) ((AmqpValue) request.getBody()).getValue();
        return requestBody.toUpperCase();
    }

    private static void sendStatusUpdates(Vertx vertx, ProtonConnection conn) {
        ProtonSender sender = conn.createSender("upstate/worker-status");

        vertx.setPeriodic(10 * 1000, (timer) -> {
                if (conn.isDisconnected()) {
                    vertx.cancelTimer(timer);
                    return;
                }

                if (sender.sendQueueFull()) {
                    return;
                }

                System.out.println("WORKER-VERTX: Sending status update");

                Map<String, Object> props = new HashMap<String, Object>();
                props.put("worker_id", conn.getContainer());
                props.put("timestamp", System.currentTimeMillis());
                props.put("count", 123);

                Message status = Message.Factory.create();
                status.setApplicationProperties(new ApplicationProperties(props));

                sender.send(status);
            });

        sender.open();
    }
}