/**
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
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package org.apache.hadoop.yarn.server.resourcemanager.amlauncher;

import com.google.common.annotations.VisibleForTesting;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.DataInputByteBuffer;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.SecretManager.InvalidToken;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.yarn.api.ApplicationConstants;
import org.apache.hadoop.yarn.api.ContainerManagementProtocol;
import org.apache.hadoop.yarn.api.protocolrecords.*;
import org.apache.hadoop.yarn.api.records.*;
import org.apache.hadoop.yarn.client.NMProxy;
import org.apache.hadoop.yarn.event.EventHandler;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.ipc.YarnRPC;
import org.apache.hadoop.yarn.security.AMRMTokenIdentifier;
import org.apache.hadoop.yarn.server.resourcemanager.RMContext;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttempt;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttemptEvent;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttemptEventType;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttemptImpl;
import org.apache.hadoop.yarn.util.ConverterUtils;

import javax.xml.bind.DatatypeConverter;
import javax.xml.crypto.Data;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The launch of the AM itself.
 */
public class TCPAMLauncher implements Runnable {

  private static final Log LOG = LogFactory.getLog(TCPAMLauncher.class);

  private final RMAppAttempt application;
  private final Configuration conf;
  private final AMLauncherEventType eventType;
  private final RMContext rmContext;
  private final Container masterContainer;

  @SuppressWarnings("rawtypes")
  private final EventHandler handler;

  public TCPAMLauncher(RMContext rmContext, RMAppAttempt application,
                       AMLauncherEventType eventType, Configuration conf) {
    this.application = application;
    this.conf = conf;
    this.eventType = eventType;
    this.rmContext = rmContext;
    this.handler = rmContext.getDispatcher().getEventHandler();
    this.masterContainer = application.getMasterContainer();
  }
  
  private Socket connect() throws IOException {
    //user tcp connection to contact CC.
    /*ContainerId masterContainerID = masterContainer.getId();*/
    String confAddr = "yarn.resourcemanager.tcpamlaucher.address";
    String confPort = "yarn.resourcemanager.tcpamlaucher.port";

    String addr = conf.get(confAddr);
    int port = conf.getInt(confPort,9997);

    LOG.info("GYF: trying to connect to "+addr+":"+port);

    return new Socket(addr,port);
    //containerMgrProxy = getContainerMgrProxy(masterContainerID);
  }

  private void close(Socket sock) throws IOException{
    sock.close();
  }
  
  private void launch() throws IOException, YarnException {
    LOG.info("Setting up container " + masterContainer
        + " for AM " + application.getAppAttemptId());
    ContainerId masterContainerID = masterContainer.getId();
    ApplicationSubmissionContext applicationContext =
            application.getSubmissionContext();
    ContainerLaunchContext launchContext =
            createAMContainerLaunchContext(applicationContext, masterContainerID);
    Token token = application.getAMRMToken();

    /**
      application attempt id format:
     appattempt_1515558297929_0002_000001
     <prefix>_<cluster_time_stamp>_<app_id>_<attempt_id>
     */
    StringBuilder sb = new StringBuilder();
    sb.append(token.getKind()).append(":").append(token.getService()).append(":")
            .append(DatatypeConverter.printHexBinary(token.getIdentifier())).append(":")
            .append(DatatypeConverter.printHexBinary(token.getPassword()));
    String s = "start_container|"+application.getAppAttemptId().toString()+"|"+
            masterContainer.getNodeId().toString()+"|"+sb.toString()+"|"+masterContainerID.toString();

    Socket sock = connect();
    ObjectOutputStream out = new ObjectOutputStream(sock.getOutputStream());
    out.writeObject(String.format("%s",s));



    LOG.info("GYF: finish launch.");
    String response = null;
    ObjectInputStream in = new ObjectInputStream(sock.getInputStream());
    try {
      response = (String)in.readObject();
    } catch (ClassNotFoundException e) {
      e.printStackTrace();
    }
    if(response==null){
      //error;

    }
    LOG.info("GYF: receive response <"+response+">.");
    close(sock);
    /*StartContainersResponse response =
        containerMgrProxy.startContainers(allRequests);*/
    /*if (response.getFailedRequests() != null
        && response.getFailedRequests().containsKey(masterContainerID)) {
      Throwable t =
          response.getFailedRequests().get(masterContainerID).deSerialize();
      parseAndThrowException(t);
    } else {
      LOG.info("Done launching container " + masterContainer + " for AM "
          + application.getAppAttemptId());
    }*/
		LOG.info("GYF exit launch");
  }
  
  private void cleanup() throws IOException, YarnException {
    Socket sock = connect();
    /*ContainerId containerId = masterContainer.getId();
    List<ContainerId> containerIds = new ArrayList<ContainerId>();
    containerIds.add(containerId);
    StopContainersRequest stopRequest =
        StopContainersRequest.newInstance(containerIds);*/
    LOG.info("GYF: do cleanup on RM.");
    ObjectOutputStream out = new ObjectOutputStream(sock.getOutputStream());


    String s = "stop_container|"+application.getAppAttemptId().toString()+
            "|"+masterContainer.getNodeId().toString();
    out.writeObject(String.format("%s",s));

    /*String response = null;
    ObjectInputStream in = new ObjectInputStream(sock.getInputStream());
    try {
      response = (String)in.readObject();
    } catch (ClassNotFoundException e) {
      e.printStackTrace();
    }
    if(response==null){
      //error;

    }
    LOG.info("GYF: receive response <"+response+">.");*/
    close(sock);

    /*StopContainersResponse response =
        containerMgrProxy.stopContainers(stopRequest);*/
    /*if (response.getFailedRequests() != null
        && response.getFailedRequests().containsKey(containerId)) {
      Throwable t = response.getFailedRequests().get(containerId).deSerialize();
      parseAndThrowException(t);
    }*/
  }

  // Protected. For tests.
  protected ContainerManagementProtocol getContainerMgrProxy(
      final ContainerId containerId) {

    final NodeId node = masterContainer.getNodeId();
    final InetSocketAddress containerManagerConnectAddress =
        NetUtils.createSocketAddrForHost(node.getHost(), node.getPort());

    final YarnRPC rpc = getYarnRPC();

    UserGroupInformation currentUser =
        UserGroupInformation.createRemoteUser(containerId
            .getApplicationAttemptId().toString());

    String user =
        rmContext.getRMApps()
            .get(containerId.getApplicationAttemptId().getApplicationId())
            .getUser();
    org.apache.hadoop.yarn.api.records.Token token =
        rmContext.getNMTokenSecretManager().createNMToken(
            containerId.getApplicationAttemptId(), node, user);
    currentUser.addToken(ConverterUtils.convertFromYarn(token,
        containerManagerConnectAddress));

    return NMProxy.createNMProxy(conf, ContainerManagementProtocol.class,
        currentUser, rpc, containerManagerConnectAddress);
  }

  @VisibleForTesting
  protected YarnRPC getYarnRPC() {
    return YarnRPC.create(conf);  // TODO: Don't create again and again.
  }

  private ContainerLaunchContext createAMContainerLaunchContext(
      ApplicationSubmissionContext applicationMasterContext,
      ContainerId containerID) throws IOException {

    // Construct the actual Container
    ContainerLaunchContext container = 
        applicationMasterContext.getAMContainerSpec();
    LOG.info("Command to launch container "
        + containerID
        + " : "
        + StringUtils.arrayToString(container.getCommands().toArray(
            new String[0])));
    
    // Finalize the container
    setupTokens(container, containerID);
    
    return container;
  }

  private void setupTokens(
      ContainerLaunchContext container, ContainerId containerID)
      throws IOException {
    Map<String, String> environment = container.getEnvironment();
    environment.put(ApplicationConstants.APPLICATION_WEB_PROXY_BASE_ENV,
        application.getWebProxyBase());
    // Set AppSubmitTime and MaxAppAttempts to be consumable by the AM.
    ApplicationId applicationId =
        application.getAppAttemptId().getApplicationId();
    environment.put(
        ApplicationConstants.APP_SUBMIT_TIME_ENV,
        String.valueOf(rmContext.getRMApps()
            .get(applicationId)
            .getSubmitTime()));
    environment.put(ApplicationConstants.MAX_APP_ATTEMPTS_ENV,
        String.valueOf(rmContext.getRMApps().get(
            applicationId).getMaxAppAttempts()));

    Credentials credentials = new Credentials();
    DataInputByteBuffer dibb = new DataInputByteBuffer();
    if (container.getTokens() != null) {
      // TODO: Don't do this kind of checks everywhere.
      dibb.reset(container.getTokens());
      credentials.readTokenStorageStream(dibb);
    }

    // Add AMRMToken
    Token<AMRMTokenIdentifier> amrmToken = createAndSetAMRMToken();
    if (amrmToken != null) {
      credentials.addToken(amrmToken.getService(), amrmToken);
    }
    DataOutputBuffer dob = new DataOutputBuffer();
    credentials.writeTokenStorageToStream(dob);
    container.setTokens(ByteBuffer.wrap(dob.getData(), 0, dob.getLength()));
  }

  @VisibleForTesting
  protected Token<AMRMTokenIdentifier> createAndSetAMRMToken() {
    Token<AMRMTokenIdentifier> amrmToken =
        this.rmContext.getAMRMTokenSecretManager().createAndGetAMRMToken(
          application.getAppAttemptId());
    ((RMAppAttemptImpl)application).setAMRMToken(amrmToken);
    return amrmToken;
  }
  
  @SuppressWarnings("unchecked")
  public void run() {
    switch (eventType) {
    case LAUNCH:
      try {
        LOG.info("GYF Launching master " + application.getAppAttemptId());
        launch();
				LOG.info("GYF begin handle event launched");
        handler.handle(new RMAppAttemptEvent(application.getAppAttemptId(),
            RMAppAttemptEventType.LAUNCHED));
				LOG.info("GYF finish handle event launched");
      } catch(Exception ie) {
        String message = "Error launching " + application.getAppAttemptId()
            + ". Got exception: " + StringUtils.stringifyException(ie);
        LOG.info(message);
        handler.handle(new RMAppAttemptEvent(application
            .getAppAttemptId(), RMAppAttemptEventType.LAUNCH_FAILED, message));
      }
		  LOG.info("GYF before break");
      break;
    case CLEANUP:
      try {
        LOG.info("Cleaning master " + application.getAppAttemptId());
        cleanup();
      } catch(IOException ie) {
        LOG.info("Error cleaning master ", ie);
      } catch (YarnException e) {
        StringBuilder sb = new StringBuilder("Container ");
        sb.append(masterContainer.getId().toString());
        sb.append(" is not handled by this NodeManager");
        if (!e.getMessage().contains(sb.toString())) {
          // Ignoring if container is already killed by Node Manager.
          LOG.info("Error cleaning master ", e);          
        }
      }
      break;
    default:
      LOG.warn("Received unknown event-type " + eventType + ". Ignoring.");
      break;
    }
  }

  private void parseAndThrowException(Throwable t) throws YarnException,
      IOException {
    if (t instanceof YarnException) {
      throw (YarnException) t;
    } else if (t instanceof InvalidToken) {
      throw (InvalidToken) t;
    } else {
      throw (IOException) t;
    }
  }
}
