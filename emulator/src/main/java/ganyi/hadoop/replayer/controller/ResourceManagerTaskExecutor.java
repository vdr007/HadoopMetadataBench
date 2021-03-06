package ganyi.hadoop.replayer.controller;

import ganyi.hadoop.replayer.controller.common.CentralController;
import ganyi.hadoop.replayer.controller.common.TaskExecutor;
import ganyi.hadoop.replayer.controller.common.TaskScheduler;
import ganyi.hadoop.replayer.message.Message;
import ganyi.hadoop.replayer.message.MessageQueue;
import ganyi.hadoop.replayer.simulator.rm.NodeManagerSimulator;
import ganyi.hadoop.replayer.simulator.rm.ResourceManagerAppMasterSimulator;


import java.util.*;

public class ResourceManagerTaskExecutor extends TaskExecutor {
    Random random;
    List<String> nmList;
    int parallel_num;

    public ResourceManagerTaskExecutor(String[] envs, MessageQueue<Message> in, MessageQueue<Message> out, CentralController parent) {
        taskType = Message.TaskType.ResourceManager;
        init(envs, in, out, parent);
        random = new Random(jobID);
        nmList = new ArrayList<>();
        parallel_num = (int)Math.ceil(userDefineParallelFactor * scheduler.baseParallelFactor);
    }

    @Override
    public void run() {
        runResourceManagerTask();
    }

    @Override
    protected void executeTuple(TaskScheduler.ExecutionTuple tuple) {
        LOG.info(">>>>> Executing tuple: " + tuple + " right now.");
        String filePrefix = tuple.execFile;
        TaskScheduler.EXEC_TYPE type = tuple.execType;
        if (type == TaskScheduler.EXEC_TYPE.MULTIPLE) {
            int num = Integer.parseInt(tuple.execArg);
            initMultipleSimulator(filePrefix, num);
        } else if (type == TaskScheduler.EXEC_TYPE.SINGLE) {
            initMultipleSimulator(filePrefix, 1);
        } else {
            //Error
        }
    }

    private void runResourceManagerTask() {
        TaskScheduler.ExecutionTuple tuple;
        while ((tuple = scheduler.getNextExeTuple()) != null) {
            executeTuple(tuple);
            while (!scheduler.isConditionReached(tuple)) {
                Message msg = pendingMessageQueue.get();
                //LOG.info("[" + jobID + "] Receive message: " + msg);
                if (msg.getMsgType() == Message.MSG_TYPE.finish_response) {

                    /*String cmd = msg.getCmd();
                    String[] ss= cmd.split("/");
                    String type = ss[ss.length-1].split("\\.")[0];*/
                    /*if(type.toLowerCase().contains("client")){*/
                    //Release app client
                    String simPoolID = configure.getSimPoolID(msg.getSrc());
                    Message releaseMsg = new Message(Message.MSG_TYPE.release_simulator,
                            msg.getSrc(), getIdentifier(), simPoolID, jobID);
                    parent.sendTCPMessage(releaseMsg);

                    /*}
                    else{
                        //
                    }*/
                } else if (msg.getMsgType() == Message.MSG_TYPE.create_simulator) {
                    String id = msg.getCmd();
                    String simPoolID = msg.getSrc();
                    configure.updateSimulatorMap(id, simPoolID);
                } else if (msg.getMsgType() == Message.MSG_TYPE.release_simulator) {
                    String type = msg.getCmd().split("\\|")[0];
                    //String type = ss[ss.length - 1].split("\\.")[0];
                    scheduler.updateProgress(type);

                    String id = msg.getCmd();
                    configure.deleteSimulatorMapEntry(id);
                    simulatorList.remove(id);
                }
                /*else if(msg.getMsgType() == Message.MSG_TYPE.stop_instance){
                    String simID = msg.getCmd();
                }*/
                /*else if(msg.getMsgType() == Message.MSG_TYPE.obtain_appid){

                }*/
                else if (msg.getMsgType() == Message.MSG_TYPE.RM_RPC_CALL) {
                    String[] request = msg.getCmd().split("\\|");
                    if (request[0].equalsIgnoreCase("start_container")) {
                        Message message;
                        message = new Message(Message.MSG_TYPE.RM_RPC_CALL,
                                msg.getCmd(), getIdentifier(), msg.getSrc(), jobID);
                        parent.sendRPCMessage(message);

                        LOG.info("RM_RPC_CALL: get msg "+msg);
                        String simID;
                        simID = parent.getNodeManagerMapping(msg.getMisc());
                        LOG.info("RM_RPC_CALL: simID" + simID);
                        //At the same time, change the state of NN holding AM.
                        //simID = "nm." + String.valueOf(Long.valueOf(msg.getMisc()) - 10005);
                        message = new Message(Message.MSG_TYPE.ChangeHBState,
                                NodeManagerSimulator.NodeManagerHB_TYPE.running.name(),
                                getIdentifier(), simID, jobID);
                        message.setMisc(request[3] + "|" + request[4]);
                        parent.sendTCPMessage(message);

                        nmList.add(simID);
                    } else if (request[0].equalsIgnoreCase("stop_container")) {
                        LOG.info(getJobID()+"GYF: receive stop message from RM. Yeah!");
                        String simID = configure.getSetting("amsim", "am-0002-01") + "|"
                                + String.valueOf(jobID);
                        Message message = new Message(Message.MSG_TYPE.RMAppMasterStatus,
                                "toReleasePhase",getIdentifier(),simID,getJobID());
                        parent.sendTCPMessage(message);

                    }
                    //System.exit(2);
                } else if (msg.getMsgType() == Message.MSG_TYPE.RMAppMasterStatus) {
                    if (msg.getCmd().equalsIgnoreCase("register")) {
                        LOG.info("AppMaster starts to allocate.");
                        Message message = new Message(Message.MSG_TYPE.ChangeHBState,
                                ResourceManagerAppMasterSimulator.ALLOCATE_TYPE.resource_request.name(),
                                getIdentifier(), msg.getSrc(), jobID);
                        parent.sendTCPMessage(message);
                    } else if (msg.getCmd().equalsIgnoreCase("finish")) {
                        LOG.info("AppMaster finishes, change NM's state to complete. (Direct jump to ending phase.)");

                        Message msg1 = new Message(Message.MSG_TYPE.RMAppMasterStatus,
                                "toReleasePhase",getIdentifier(),msg.getSrc(),getJobID());
                        parent.sendTCPMessage(msg1);

                        HashMap<String, List<String[]>> hashMap = new HashMap<>();
                        for (String simID: nmList){
                            String poolId = configure.getSimPoolID(simID);
                            if (hashMap.containsKey(poolId)){
                                List<String[]> value = hashMap.get(poolId);
                                value.add(new String[]{simID,NodeManagerSimulator.NodeManagerHB_TYPE.complete.name()});
                            }
                            else {
                                List<String[]> value = new ArrayList<>();
                                value.add(new String[]{simID,NodeManagerSimulator.NodeManagerHB_TYPE.complete.name()});
                                hashMap.put(poolId, value);
                            }
                        }
                        for(Map.Entry<String, List<String[]>> entry: hashMap.entrySet()){
                            String poolID = entry.getKey();
                            StringJoiner sj = new StringJoiner("|");
                            for (String[] strings: entry.getValue()){
                                sj.add(strings[0]+":"+strings[1]);
                            }
                            Message message = new Message(Message.MSG_TYPE.batch_processing,
                                    Message.MSG_TYPE.ChangeHBState.name(),getIdentifier(),
                                    poolID,jobID);
                            message.setMisc(sj.toString());
                            parent.sendTCPMessage(message);
                        }
                        /*
                        for (String simID: nmList){
                            Message message = new Message(Message.MSG_TYPE.ChangeHBState,
                                    NodeManagerSimulator.NodeManagerHB_TYPE.complete.name(),
                                    getIdentifier(),simID,jobID);
                            parent.sendTCPMessage(message);
                        }*/
                    } else if (msg.getCmd().equalsIgnoreCase("start_nm_container")) {
                        //receive container from RM_APP, change NM's state on demand
                        String[] ss = msg.getMisc().split("\\|");

                        HashMap<String, List<String[]>> hashMap = new HashMap<>();
                        for(String s: ss){      //iterate all sims, put them to where they should go.
                            //
                            String inet = s.split("#")[0];
                            String simID = parent.getNodeManagerMapping(inet);
                            //String simID = "nm."+String.valueOf(Integer.valueOf(s.split("#")[0])-10005);
                            String time = String.valueOf(random.nextInt(
                                    Integer.valueOf(configure.getSetting("jtrange", "10")))
                                    + Integer.valueOf(configure
                                    .getSetting("jtbase", "20")
                            ));
                            String containerId = s.split("#")[1];
                            String poolId = configure.getSimPoolID(simID);
                            if (hashMap.containsKey(poolId)){
                                List<String[]> value = hashMap.get(poolId);
                                value.add(new String[]{simID,time,containerId});
                            }
                            else{
                                List<String[]> value = new ArrayList<>();
                                value.add(new String[]{simID,time,containerId});
                                hashMap.put(poolId,value);
                            }
                            nmList.add(simID);
                        }
                        for(Map.Entry<String,List<String[]>> entry: hashMap.entrySet()){
                            String poolID = entry.getKey();
                            StringJoiner sj = new StringJoiner("|");
                            for(String[] strings: entry.getValue()){
                                sj.add(strings[0]+":"+strings[1]+":"+strings[2]);       //simID, time, containerId
                            }
                            Message message = new Message(Message.MSG_TYPE.batch_processing,
                                    "start_container",getIdentifier(),poolID,jobID);
                            message.setMisc(sj.toString());
                            parent.sendTCPMessage(message);
                        }

                        /*for (String s : ss) {
                            int port = Integer.valueOf(s.split(":")[0]);
                            String nodeManagerId = "nm." + String.valueOf(port - 10005);
                            //start pseudo container/job on NM.

                            int time = random.nextInt(
                                    Integer.valueOf(configure.getSetting("jtrange", "10")))
                                    + Integer.valueOf(configure
                                    .getSetting("jtbase", "10"));
                            Message message = new Message(
                                    Message.MSG_TYPE.nm_message, "start_container",
                                    getIdentifier(), nodeManagerId, jobID);
                            String misc = String.valueOf(time) + ":" + s.split(":")[1];
                            message.setMisc(misc);
                            parent.sendTCPMessage(message);

                            nmList.add(nodeManagerId);
                        }*/
                    } else {
                        throw new RuntimeException("Wrong arugments in " +
                                "message<RMAppMasterStatus>. Cmd: " + msg.getCmd());
                    }
                } else if (msg.getMsgType() == Message.MSG_TYPE.nm_message) {
                    if (msg.getCmd().equalsIgnoreCase("finish_container")) {
                        //update progress on AM.
                        String simID = configure.getSetting("amsim", "am-0002-01") + "|"
                                + String.valueOf(jobID);
                        Message message = new Message(Message.MSG_TYPE.RMAppMasterStatus,
                                "progress", getIdentifier(), simID, jobID);
                        parent.sendTCPMessage(message);
                    } else if (msg.getCmd().equalsIgnoreCase("amnm_started")) {
                        //send message to start application master.
                        LOG.info("create AM simulator and start AM.");
                        String simPoolID = getNextSimpoolID();
                        String simID = scheduler.findAvailable(configure.getSetting("amsim", "am-0002-01"))
                                + "|" + String.valueOf(jobID);

                        configure.updateSimulatorMap(simID, simPoolID);
                        simulatorList.add(simID);

                        String cmd = simID;
                        Message message = new Message(Message.MSG_TYPE.create_simulator,
                                cmd, getIdentifier(), simPoolID, jobID, taskType);
                        //String misc = String.valueOf(scheduler.num_dn - 1) + "|" + msg.getMisc() + "|" + msg.getSrc();
                        String misc = String.valueOf(parallel_num) + "|" + msg.getMisc() + "|" + msg.getSrc();
                        message.setMisc(misc);
                        parent.sendTCPMessage(message);

                        message = new Message(Message.MSG_TYPE.command,
                                "start#" + scheduleFolder + simID.split("\\|")[0] + ".csv",
                                getIdentifier(), simID, jobID);
                        parent.sendTCPMessage(message);

                    } else if (msg.getCmd().equalsIgnoreCase("finish_am_container")) {
                        LOG.info("am's container is released");
                    }
                    //
                } else {
                    LOG.error("receive a message sent to wrong place.\tMessage: " + msg);
                }
            }
        }
        destroy();
        try {
            Thread.sleep(5 * 1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        respondWithFinish();
        while (true) {
            Message message = pendingMessageQueue.get();
            if (message.getMsgType() == Message.MSG_TYPE.release_jobExecutor) {
                break;
            }
        }
    }
}
