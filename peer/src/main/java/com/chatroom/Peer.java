package com.chatroom;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.*;
// packages for argument parsing
import com.alibaba.fastjson.JSON;
import com.chatroom.base.Packets.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
// Jackson library
import com.fasterxml.jackson.databind.ObjectMapper;
// packages for FastJson
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONArray;
import java.net.InetAddress;

import static java.lang.Thread.sleep;

public class Peer {

    @Option(name="-p", usage = "Port number used to listen")
    private static int PORT_P = 4444;
    @Option(name="-i", usage = "Port number used to connect")
    private static int PORT_I;

    private Client client;
    private Server server;
    private Boolean background = false;
    private Boolean canMigrate;
    private Boolean migrating = false;
    private static int ackToReceive;

    public Peer() {
    }

    public String combine(String hostname, int port){ return hostname + ":" + port;}

    class Room {
        private String roomid;
        private List<Connection> connections = new ArrayList<>();
        private int count = 0;

        public Room(String roomid) {
            this.roomid = roomid;
        }

        public String getRoomid() { return this.roomid; }

        /** functions same as project 1 */
        public void add(Connection connection) {
            this.connections.add(connection);
            this.count++;
        }

        public void remove(Connection connection) {
            this.connections.remove(connection);
            this.count--;
        }

        public int getCount() { return this.count; }

        public List<Connection> getConnections() {
            // deep copy
            List<Connection> connections = new ArrayList<>();
            for (Connection connection : this.connections){
                connections.add(connection);
            }
            return connections;
        }

        private List<String> getIdentities() {
            List<String> identities = new ArrayList<>();
            for (Connection connection : this.connections) {
                identities.add(connection.getIdentity());
            }
            return identities;
        }

    }

    class Connection {
        private int port;
        private String identity = null;
        private boolean isClient;   // false: server connection; true client connection
        private Room room = null;
        private Socket socket;
        private BufferedReader reader;
        private PrintWriter writer;

        public Connection(Socket socket, Boolean isClient) throws IOException {
            this.socket = socket;
            this.isClient = isClient;

            if (isClient) {
                this.identity = client.identity;
            } else{
                // can only get port from server end; hostname is get from hostchange
                this.port = socket.getPort();
            }
            this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            this.writer = new PrintWriter(socket.getOutputStream());
        }

        // only address is needed since port is known
        public void setIdentity(String address) { this.identity = combine(address, this.port); }

        public String getIdentity() { return this.identity; }

        public void join(Room room) { this.room = room; }

        public void leave() { this.room = null; }

        public Room getRoom() { return this.room; }

        public String getRoomId() {
            if (this.room == null) return "";
            else return this.room.getRoomid();
        }

        public String read() throws IOException {
            String message = reader.readLine();
            return message;
        }

        private void write(String json){
            writer.println(json);
            writer.flush();
        }

        public String getRoomList(JSONArray roomList) {
            StringBuilder sb = new StringBuilder();
            for(int i=0;i<roomList.size();i++) {
                String roomid = (String) roomList.getJSONObject(i).get("roomid");
                Integer count = Integer.valueOf((String)roomList.getJSONObject(i).get("count"));
                String user = ((count <= 1) ? "user" : "users");
                sb.append(String.format("%s: %s %s\n", roomid, count, user));
            }
            return sb.toString();
        }

        public void close() {
            try {
                socket.close();
                reader.close();
                writer.close();
            } catch (IOException e) {
                System.out.println(e.getMessage());
            }
        }

        /**
         * Processed by client
         * @param packet
         */
        private void handleRoomChange(JSONObject packet){
            if (background){
                close();
            }else {
                client.newLine(); //print new line ?
                String identity = (String) packet.get("identity");
                String roomid = (String) packet.get("roomid");
                String former = (String) packet.get("former");
                if (client.remainToReceive.contains(roomid)) client.remainToReceive.remove(roomid);
                if (former.equals(roomid)) {
                    // invalid room id or roomid already in use
                    System.out.println(former);
                    System.out.println(roomid);
                    System.out.print("The requested room is invalid or not existent\n");
                    // not expect for room contents
                    if (client.remainToReceive.contains("roomcontents")) client.remainToReceive.remove("roomcontents");
                } else {
                    // handle the case for someone leaving / delete
                    // also handle the case for quit
                    if (roomid.equals("")) {
                        if (client.remainToReceive.contains("quit")) {
                            client.remainToReceive.remove("quit");
                            System.out.printf("%s leaves %s\n", identity, former);
                            System.out.printf("Disconnected from %s\n", client.connectHost);
                            client.connection = null;
                            close();
                            //System.exit(1);
                        } else {
                            //
                            System.out.printf("%s leaves %s\n", identity, former);
                            // if someone leaves himself,
                            if (identity.equals(client.identity)) client.roomid = null;
                        }
                    } else {
                        // handle the case for someone just joined
                        if (former.equals("")) {
                            // do not print message while migrating
                            if (!migrating) System.out.printf("%s moves to %s\n", identity, roomid);
                        } else {
                            // successfully change room
                            System.out.printf("%s moved from %s to %s\n", identity, former, roomid);
                        }
                        // if the client requested a roomchange himself
                        if (identity.equals(client.identity)) client.roomid = roomid;
                    }
                }
                if (!migrating) {
                    client.setToPrint();
                    client.printPrefix();
                }
            }
        }

        /**
         * Processed by client
         * @param packet
         */
        private void handleRoomContents(JSONObject packet){
            //TODO
            client.newLine();
            if (client.remainToReceive.contains("roomcontents")) client.remainToReceive.remove("roomcontents");
            String roomid = (String) packet.get("roomid");
            JSONArray identities = (JSONArray) packet.get("identities");
            if (identities == null) {
                System.out.printf("Room %s does not exist\n", roomid);
            } else {
                StringBuilder sb = new StringBuilder();
                sb.append(String.format("%s contains ", roomid));
                for (int i=0; i<identities.size(); i++) {
                    String identity = (String) identities.get(i);
                    if (i == identities.size()-1) {sb.append(String.format("%s", identity));}
                    else {sb.append(String.format("%s, ", identity));}
                }
                sb.append("\n");
                System.out.print(sb);
            }
            client.printPrefix();
        }

        /**
         * Processed by client
         * @param packet
         * @throws JsonProcessingException
         */
        private void handleRoomList(JSONObject packet) throws JsonProcessingException {
            //TODO
            JSONArray roomList = (JSONArray) packet.get("rooms");
            if (background) {
                System.out.print(getRoomList(roomList));
                sendQuit();
            }else {
                client.newLine();
                if (client.remainToReceive.contains("roomlist")) client.remainToReceive.remove("roomlist");
                // handle the case when client requested create room
                List<String> existedRoomids = new ArrayList<>();
                for (int i = 0; i < roomList.size(); i++) {
                    String roomid = (String) roomList.getJSONObject(i).get("roomid");
                    existedRoomids.add(roomid);
                }
                String roomlistString = client.getRoomList(roomList);
                System.out.printf("%s", roomlistString);
                client.printPrefix();
            }
        }

        /**
         * Processed by client
         * @param packet
         * @throws JsonProcessingException
         */
        private void handleServerMessage(JSONObject packet) throws JsonProcessingException {
            if (!client.toPrint) client.newLine();
            String identity = (String) packet.get("identity");
            String content = (String) packet.get("content");
            System.out.printf("%s: %s\n", identity, content);
            // if (client.remainToReceive.contains(identity)) client.remainToReceive.remove(identity);
            client.setToPrint();
            client.printPrefix();
        }

        private void handleNeighbors(JSONObject packet) throws JsonProcessingException {
            //client.newLine();
            JSONArray neighbors = (JSONArray) packet.get("neighbors");
            if (background){
                List<String> neighborsList = JSONObject.parseArray(neighbors.toJSONString(), String.class);
                client.joinSearchQueue(neighborsList);
                sendList();
            } else {
                if (client.remainToReceive.contains("listneighbors")) client.remainToReceive.remove("listneighbors");
                StringBuilder sb = new StringBuilder();
                sb.append(String.format("["));
                for (int i = 0; i < neighbors.size(); i++) {
                    sb.append(String.format("%s", neighbors.get(i)));
                    if (i < neighbors.size() - 1) sb.append(String.format(" ", neighbors.get(i)));
                }
                sb.append("]\n");
                System.out.print(sb);
                client.setToPrint();
                client.printPrefix();
            }
        }

        public void handleConfirm(JSONObject packet) throws JsonProcessingException {
            String host = (String) packet.get("host");
            boolean available = client.tryConnect(host);
            sendAcknowledge(available);
        }

        public void handleMigrate(JSONObject packet) throws JsonProcessingException {
            String host = (String) packet.get("host");
            try {
                client.reConnect(host);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        public void handleRoomInfo(JSONObject packet) throws JsonProcessingException {
            JSONArray roomInfo = (JSONArray) packet.get("roomInfo");
            List<String> roominfo = JSONObject.parseArray(roomInfo.toJSONString(), String.class);
            background = true;
            for (String roomid : roominfo){
                if (!server.roomids.contains(roomid)){
                    server.createRoom(roomid);
                }
            }
            background = false;
            this.sendFitRoom();
            server.remove(this);
        }

        /**
         * Processed by server
         * @param packet
         * @throws JsonProcessingException
         */
        private void handleJoin(JSONObject packet) throws JsonProcessingException {
            //TODO
            String roomid = (String) packet.get("roomid");
            String formerid = getRoomId();
            if ((!server.roomids.contains(roomid)) || (getRoomId().equals(roomid))) {
                // invalid or same room id, also leaving
                if (roomid.equals("")) {
                    // handle leaving
                    sendRoomChange(getIdentity(), getRoomId(), "");
                    Room former = getRoom();
                    former.remove(this);
                    this.room = null;
                } else {
                    // handle invalid or already in use
                    sendRoomChange(getIdentity(), getRoomId(), getRoomId());
                }
            } else {
                Room former = getRoom();
                Room room = server.findRoom(roomid);
                server.updateConnectionRoom(this, room);
                if (former != null) {
                    for (Connection receiver : former.getConnections()) {
                        receiver.sendRoomChange(getIdentity(), former.getRoomid(), roomid);
                    }
                }
                for (Connection receiver : room.getConnections()) {
                    receiver.sendRoomChange(getIdentity(), formerid, roomid);
                }
                // sendRoomContents(roomid, server.getRoomContents(room, getIdentity()));
            }
        }

        /**
         * Processed by server
         * @param packet
         * @throws JsonProcessingException
         */
        private void handleWho(JSONObject packet) throws JsonProcessingException {
            String roomid = (String) packet.get("roomid");
            if (server.roomids.contains(roomid)) {
                Room room = server.findRoom(roomid);
                List<String> identities = server.getRoomContents(room, this.identity);
                sendRoomContents(roomid, identities);
            } else {
                sendRoomContents(roomid, null);
            }
        }

        /**
         * server's response to the roomlist protocol
         *
         */
        private void handleList() throws JsonProcessingException {
            //TODO
            sendRoomList(server.getRoomList());
        }

        private void handleListNeighbors() throws JsonProcessingException {
            //TODO
            List<String> neighborsList = new ArrayList<>();
            for (Map.Entry<Connection, String> entry : server.neighbours.entrySet()) {
                Connection c = entry.getKey();
                String host = entry.getValue();
                if (!c.getIdentity().equals(this.getIdentity()) && !host.equals(client.localHost)){
                    neighborsList.add(host);
                }
            }
            this.sendNeighbors(neighborsList);
        }

        private void handleHostChange(JSONObject packet){
            //TODO
            String host = (String) packet.get("host");
            server.addNeighbor(this, host);
            String[] arr = host.split(":");
            this.setIdentity(arr[0]);
        }

        private void handleClientMessage(JSONObject packet) throws JsonProcessingException {
            String content = (String) packet.get("content");
            String identity = getIdentity();
            Room room = getRoom();
            for (Connection receiver : room.getConnections()) {
                receiver.sendServerMessage(identity, content);
            }
        }

        /**
         * Processed by server
         * @param packet
         * @throws JsonProcessingException
         */
        private void handleQuit(JSONObject packet) throws JsonProcessingException {
            String identity = this.getIdentity();
            if (identity == null){
                // background search connection
                this.sendRoomChange("", "empty", "");
            }else {
                Room room = this.getRoom();
                String roomId = this.getRoomId();
                server.updateConnectionRoom(this, null);
                if (room != null) {
                    for (Connection receiver : room.getConnections()) {
                        // broadcast quit message
                        receiver.sendRoomChange(identity, roomId, "");
                    }
                }
                this.sendRoomChange(identity, "empty", "");
                server.remove(this);
            }
        }

        public void handleAcknowledge(JSONObject packet) {
            ackToReceive--;
            boolean available = (boolean) packet.get("available");
            if (!available) canMigrate = false;
        }

        public void handleFitRoom(JSONObject packet) throws JsonProcessingException{
            //TODO
            ackToReceive--;
            this.close();
        }

        public void sendJoin(String roomid) throws JsonProcessingException {
            Join join = new Join();
            join.set(roomid);
            ObjectMapper om = new ObjectMapper();
            String json = om.writeValueAsString(join);
            this.write(json);
            client.setToReceive(roomid);
            client.printPrefix();
        }

        public void sendWho(String roomid) throws JsonProcessingException {
            Who who = new Who();
            who.set(roomid);
            ObjectMapper om = new ObjectMapper();
            String json = om.writeValueAsString(who);
            this.write(json);
            client.setToReceive("roomcontents");
            client.printPrefix();
        }

        /**
         * Processed by client
         * @throws JsonProcessingException
         */
        public void sendList() throws JsonProcessingException {
            IdentityList list = new IdentityList();
            ObjectMapper objectMapper = new ObjectMapper();
            String json = objectMapper.writeValueAsString(list);
            this.write(json);
            if (!background) {
                client.setToReceive("roomlist");
                client.printPrefix();
            }
        }

        /**
         * Processed by client
         * @throws JsonProcessingException
         */
        public void sendListNeighbors() throws JsonProcessingException {
            ListNeighbors listNeighbors = new ListNeighbors();
            ObjectMapper objectMapper = new ObjectMapper();
            String json = objectMapper.writeValueAsString(listNeighbors);
            this.write(json);
            if (!background) {
                client.setToReceive("listneighbors");
                client.printPrefix();
            }
        }

        public void sendHostChange(String host) throws JsonProcessingException {
            //TODO
            HostChange change = new HostChange();
            change.set(host);
            ObjectMapper objectMapper = new ObjectMapper();
            String json = objectMapper.writeValueAsString(change);
            this.write(json);
        }

        public void sendClientMessage(String content) throws JsonProcessingException {
            ClientMessage message = new ClientMessage();
            message.set(content);
            ObjectMapper objectMapper = new ObjectMapper();
            String json = objectMapper.writeValueAsString(message);
            this.write(json);
        }

        public void sendQuit() throws JsonProcessingException {
            Quit quit = new Quit();
            ObjectMapper objectMapper = new ObjectMapper();
            String json = objectMapper.writeValueAsString(quit);
            this.write(json);
            if (!background) {
                client.setToReceive("quit");
                client.printPrefix();
            }
        }

        public void sendAcknowledge(boolean available) throws JsonProcessingException {
            Acknowledge acknowledge = new Acknowledge();
            acknowledge.set(available);
            ObjectMapper objectMapper = new ObjectMapper();
            String json = objectMapper.writeValueAsString(acknowledge);
            this.write(json);
        }

        public void sendFitRoom() throws JsonProcessingException {
            FitRoom fitRoom = new FitRoom();
            ObjectMapper objectMapper = new ObjectMapper();
            String json = objectMapper.writeValueAsString(fitRoom);
            this.write(json);
        }

        /** server message */
        public void sendRoomChange(String identity, String formerRoomid, String roomid) throws JsonProcessingException {
            RoomChange roomChange = new RoomChange();
            roomChange.set(identity, formerRoomid, roomid);
            ObjectMapper om = new ObjectMapper();
            String json = om.writeValueAsString(roomChange);
            this.write(json);
        }

        /**
         * Will be called by server
         * @param roomid
         * @param identities
         * @throws JsonProcessingException
         */
        public void sendRoomContents(String roomid, List<String> identities) throws JsonProcessingException {
            RoomContents roomContents = new RoomContents();
            roomContents.set(roomid, identities);
            ObjectMapper om = new ObjectMapper();
            String json = om.writeValueAsString(roomContents);
            this.write(json);
        }

        /**
         * Will be called by server
         * @param list
         * @throws JsonProcessingException
         */
        public void sendRoomList(List<Map<String, Object>> list) throws JsonProcessingException {
            RoomList roomList = new RoomList();
            roomList.set(list);
            ObjectMapper om = new ObjectMapper();
            String json = om.writeValueAsString(roomList);
            this.write(json);
        }

        public void sendServerMessage(String identity, String content) throws JsonProcessingException {
            ServerMessage serverMessage = new ServerMessage();
            serverMessage.set(identity, content);
            ObjectMapper om = new ObjectMapper();
            String json = om.writeValueAsString(serverMessage);
            this.write(json);
        }

        public void sendNeighbors(List<String> n) throws JsonProcessingException {
            Neighbors neighbors = new Neighbors();
            neighbors.set(n);
            ObjectMapper om = new ObjectMapper();
            String json = om.writeValueAsString(neighbors);
            this.write(json);
        }

        public void sendConfirm(String host) throws JsonProcessingException {
            Confirm confirm = new Confirm();
            confirm.set(host);
            ObjectMapper om = new ObjectMapper();
            String json = om.writeValueAsString(confirm);
            this.write(json);
        }

        public void sendMigrate(String host) throws JsonProcessingException {
            Migrate migrate = new Migrate();
            migrate.set(host);
            ObjectMapper om = new ObjectMapper();
            String json = om.writeValueAsString(migrate);
            this.write(json);
        }

        public void sendRoomInfo(List<String> roominfo) throws JsonProcessingException {
            RoomInfo roomInfo = new RoomInfo();
            roomInfo.set(roominfo);
            ObjectMapper om = new ObjectMapper();
            String json = om.writeValueAsString(roomInfo);
            this.write(json);
        }

        public void reply(JSONObject packet) throws JsonProcessingException {
            String type = (String) packet.get("type");
            if(isClient){
                switch (type) {
                    case "roomchange":
                        handleRoomChange(packet);
                        break;
                    case "roomcontents":
                        handleRoomContents(packet);
                        break;
                    case "roomlist":
                        handleRoomList(packet);
                        break;
                    case "message":
                        handleServerMessage(packet);
                        break;
                    case "neighbors":
                        handleNeighbors(packet);
                        break;
                    case "confirm":
                        handleConfirm(packet);
                        break;
                    case "migrate":
                        handleMigrate(packet);
                        break;
                    case "fitroom":
                        handleFitRoom(packet);
                        break;
                }
            } else{
                switch (type) {
                    case "join":
                        handleJoin(packet);
                        break;
                    case "who":
                        handleWho(packet);
                        break;
                    case "list":
                        handleList();
                        break;
                    case "listneighbors":
                        handleListNeighbors();
                        break;
                    case "hostchange":
                        handleHostChange(packet);
                        break;
                    case "message":
                        handleClientMessage(packet);
                        break;
                    case "quit":
                        handleQuit(packet);
                        break;
                    case "acknowledge":
                        handleAcknowledge(packet);
                        break;
                    case "roominfo":
                        handleRoomInfo(packet);
                        break;
                }
            }
        }
    }

    class Client {
        // private Room roomid = null;
        private String roomid;
        private String identity;    // hostname plus outgoing port; show as prefix
        private int port;
        private String hostname;
        private String localHost;   // host that can be connected by other peers
        private String connectHost; // host that currently connect to
        private Socket socket;
        private Connection connection = null;
        private boolean toPrint = true;
        private boolean lineFeed;
        private List<String> remainToReceive = new ArrayList<>(); // a bit of unsure whether it is needed
        private Queue<String> searchQueue;
        private List<String> exploredHost;

        private BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        public Client(String hostname, int port) {
            this.hostname = hostname;
            this.port = port;
            this.identity = combine(this.hostname, this.port);
            this.localHost = combine(this.hostname, PORT_P);
            System.out.println("Client " + this.identity + " launches successfully");
        }

        /** connect to target host */
        public void connect(String hostname, int port) throws IOException {
            if (this.connection == null) {
                this.connectHost = combine(hostname, port);
                this.socket = new Socket(hostname, port, InetAddress.getLocalHost(), this.port);
                // get the allocated port
                this.port = socket.getLocalPort();
                this.identity = combine(this.hostname, this.port);
                this.connection = new Connection(this.socket, true);
                // add connected peer to neighbors
                server.addNeighbor(this.connection, this.connectHost);
                new InteractWithPeer(this.connection).start();
                this.connection.sendHostChange(this.localHost);
            }
            if (!migrating) {
                client.toPrint = true;
                client.printPrefix();
            }
        }

        public void connect(String hostname, int port, int connectPort) throws IOException {
            if (this.connection == null) {
                this.connectHost = combine(hostname, port);
                this.socket = new Socket(hostname, port, InetAddress.getLocalHost(), connectPort);
                // update outgoing port and identity
                this.port = socket.getLocalPort();
                this.identity = combine(this.hostname, this.port);
                this.connection = new Connection(this.socket, true);
                // add connected peer to neighbors
                server.addNeighbor(this.connection, this.connectHost);
                new InteractWithPeer(connection).start();
                this.connection.sendHostChange(this.localHost);
            }
            if (!migrating) {
                client.toPrint = true;
                client.printPrefix();
            }
        }

        public boolean tryConnect(String host) {
            boolean success = true;
            String[] arr = host.split(":");
            Connection testConnection = null;
            try {
                socket = new Socket(arr[0], Integer.parseInt(arr[1].trim()));
                testConnection = new Connection(socket, true);
                testConnection.sendQuit();
            } catch (IOException e) {
                success = false;
            }
            testConnection.close();
            return success;
        }

        public void reConnect(String host) throws IOException {
            //TODO
//            if (!host.equals(localHost)) {
            String[] arr = host.split(":");
            String targetRoomid = this.roomid;  // the room should be connected to when reconnect
            // initialize
            this.roomid = null;
            migrating = true;
            connection.sendQuit();
            connection.close();
            connection = null;
            this.roomid = null;
            try{
                // wait the release of binded port
                sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            connect(arr[0], Integer.parseInt(arr[1].trim()));
            System.out.println("Migrate to host " + host);
            migrating = false;
            client.toPrint = true;
            client.printPrefix();
            join(targetRoomid);

        }

        public void join(String roomid) throws IOException {
            if (this.connection == null) {
                System.out.println("Invalid operation: no connection");
                client.toPrint = true;
                // System.out.println("remain to receive size " + client.remainToReceive.size());
                client.printPrefix();
            }else {
                connection.sendJoin(roomid);
            }
        }

        public void who(String roomid) throws IOException {
            if (this.connection == null) {
                System.out.println("Invalid operation: no connection");
                client.toPrint = true;
                client.printPrefix();
            }else {
                //TODO
                connection.sendWho(roomid);
            }
        }

        public void list() throws IOException {
            if (this.connection == null) {
                System.out.println("Invalid operation: no connection");
                client.toPrint = true;
                client.printPrefix();
            }else {
                //TODO
                connection.sendList();
            }
        }

        public void quit() throws IOException {
            if (this.connection == null) {
                System.out.println("Invalid operation: no connection");
                client.toPrint = true;
                client.printPrefix();
            }else {
                //TODO
                connection.sendQuit();
            }
        }

        public void close() throws IOException {
            if (this.connection != null) {
                connection.sendQuit();
                connection.close();
                reader.close();
            }
        }

        public void searchNetwork() throws IOException {
            searchQueue = new LinkedList<>();
            exploredHost = new ArrayList<>();
            exploredHost.add(this.localHost);
            background = true;
            if (this.connection != null) {
                // search connected server
                searchQueue.add(connectHost);
                exploredHost.add(connectHost);
            }
            // search local server
            joinSearchQueue(server.getNeighborHosts());
            String host;    // host to explore
            while ((host = searchQueue.poll()) != null){
                System.out.println(host);
                String[] arr = host.split(":");
                Socket searchSocket = new Socket(arr[0], Integer.parseInt(arr[1].trim()));
                Connection searchConnection = new Connection(searchSocket, true);
                InteractWithPeer searchThread = new InteractWithPeer(searchConnection);
                searchThread.start();
                searchConnection.sendListNeighbors();
                Boolean alive = true;
                while (alive){
                    if (!searchThread.isAlive())
                        alive = false;
                }
            }
            background = false;
            client.toPrint = true;
            client.printPrefix();
        }

        public void joinSearchQueue(List<String> neighbors){
            for (String neighbor : neighbors){
                if (!exploredHost.contains(neighbor)) {
                    searchQueue.add(neighbor);
                    exploredHost.add(neighbor);
                }
            }
        }

        public void sendMessage(String message) throws IOException {
            if (this.connection == null) {
                System.out.println("Invalid operation: no connection");
                client.toPrint = true;
                //System.out.println("remain to receive size " + client.remainToReceive.size());
                client.printPrefix();
            }else {
                //TODO
                if (this.roomid == null) {
                    System.out.println("Not in a room");
                    client.toPrint = true;
                    client.printPrefix();
                } else {
                    connection.sendClientMessage(message);
                }
            }
        }

        public void listNeighbors() throws IOException {
            if (this.connection == null) {
                System.out.println("Invalid operation: no connection");
                client.toPrint = true;
                //System.out.println("remain to receive size " + client.remainToReceive.size());
                client.printPrefix();
            } else {
                connection.sendListNeighbors();
            }
        }

        /** read from local input */
        public String read() throws IOException {
            String message = reader.readLine();
            return message;
        }

        private void printPrefix () {
            if (toPrint && remainToReceive.size() <= 0) {
                if (connection != null && !connection.socket.isClosed()) {
                    try {
                        if (roomid != null) {
                            System.out.printf("[%s] %s:%d>", roomid, InetAddress.getLocalHost().getHostAddress(), this.port);
                        } else {
                            System.out.printf("[] %s:%d>", InetAddress.getLocalHost().getHostAddress(), this.port);
                        }
                    } catch (IOException e) {
                        System.out.println("print failure" + e);
                    }
                } else {
                    System.out.printf("[] %s>", localHost);
                }
                toPrint = false;
                lineFeed = true;
            } else {
                //TODO: for what
            }
        }

        private void newLine(){
            if (lineFeed) { System.out.println(); lineFeed = false; }
        }

        private void setToPrint(){ toPrint = true; lineFeed = false; }

        private void setToReceive(String...args){
            for (String arg : args){
                remainToReceive.add(arg);
            }
        }

        /**
         * A helper method which prints all roomid and contents, called
         * by handleRoomList
         * @param roomList - JSONArray object contains all room info
         */
        public String getRoomList(JSONArray roomList) {
            StringBuilder sb = new StringBuilder();
            for(int i=0;i<roomList.size();i++) {
                String roomid = (String) roomList.getJSONObject(i).get("roomid");
                Integer count = Integer.valueOf((String)roomList.getJSONObject(i).get("count"));
                String user = ((count <= 1) ? "user" : "users");
                sb.append(String.format("%s: %s %s\n", roomid, count, user));
            }
            return sb.toString();
        }
    }

    class Server {
        private ServerSocket serverSocket;
        private List<Room> rooms = new ArrayList<>();
        private List<String> roomids = new ArrayList<>();
        private Map<Connection, String> neighbours = new HashMap<>();
        private List<Connection> connections = new ArrayList<>();
        private List<String> blackSheet = new ArrayList<>();

        //TODO: remove connecthost from neighbors if client disconnect

        public Server(int port) throws IOException {
            this.serverSocket = new ServerSocket(port);
            InetAddress addr = InetAddress.getLocalHost();
            String hostname = addr.getHostAddress();
            System.out.println("Server " + hostname + ":" + port + " launches successfully");
        }

        public Connection accept() throws IOException {
            Socket socket = this.serverSocket.accept();
            String address = socket.getLocalAddress().getHostAddress();
            if(blackSheet.contains(address)){
                socket.close();
                return null;
            } else {
                Connection connection = new Connection(socket, false);
                connections.add(connection);
                return connection;
            }
        }

        public void addNeighbor(Connection connection, String host){
            neighbours.put(connection, host);
        }

        public List<String> getNeighborHosts(){
            List<String> neighborHosts = new ArrayList<>();
            for (Map.Entry<Connection, String> entry : neighbours.entrySet()) {
                String host = entry.getValue();
                if (!host.equals(client.localHost)){
                    neighborHosts.add(host);
                }
            }
            return neighborHosts;
        }

        private void updateConnectionRoom(Connection connection, Room room) {
            Room formerRoom = connection.getRoom();
            if (formerRoom != null) {
                // remove connection from former room
                formerRoom.remove(connection);
                connection.leave();
            }
            // if room is null, means someone is leaving.
            if (room != null && !room.equals(formerRoom)) {
                // move to new room
                room.add(connection);
                connection.join(room);
            }
        }

        public void delete(String roomid) throws JsonProcessingException {
            Room room = findRoom(roomid);
            //TODO
            if (room != null){
                for (Connection connection : room.getConnections()) {
                    connection.sendRoomChange(connection.getIdentity(), connection.getRoomId(), "");
                    updateConnectionRoom(connection, room);
                }
                rooms.remove(room);
                roomids.remove(room.getRoomid());
                System.out.println("Room " + roomid + " is deleted");
            } else {
                System.out.println("Fail to delete");
            }
            client.setToPrint();
            client.printPrefix();
        }

        private List<String> getRoomContents(Room room, String exclusion){
            List<String> identities = new ArrayList<>();
            for (String identity : room.getIdentities()){
                if (!identity.equals(exclusion)){   // do not include requesting client
                    identities.add(identity);
                }
            }
            return identities;
        }

        public void help() {
            System.out.println("#help - list this information");
            System.out.println("#connect IP[:port] [local port] - connect to another peer");
            System.out.println("#quit - disconnect from a peer");
            System.out.println("#join [roomid] - join a room or change to a new room of peer");
            System.out.println("#who [roomid] - request a RoomContents message");
            System.out.println("#list - get the list of roomids and number of peer in it");
            System.out.println("#createroom [roomid] - create a room locally");
            System.out.println("#listneighbors - request the peer to get its connected peer");
            System.out.println("#delete [roomid] - delete a room ");
            System.out.println("#kick [identity] -  kick peer with identity and block peer's IP");
            System.out.println("#searchnetwork - a local command that causes the peer to do a breadth first search " +
                                "of all peers available to it");
            System.out.println("#migrate [room1id] [room2id] migrate local room1 to local room2");
            client.setToPrint();
            client.printPrefix();
        }

        public void close(){
            //TODO
            // get neighbors
            migrate();
        }

        /** remove connection from server */
        public void remove(Connection connection) throws JsonProcessingException {
            //TODO
            Room room = connection.getRoom();
            if (room != null){
                room.remove(connection);
                for (Connection receiver : room.getConnections()){
                    receiver.sendRoomChange(connection.getIdentity(), room.getRoomid(), "");
                }
            }
            neighbours.remove(connection);
            connections.remove(connection);
            connection.close();
        }

        /** kick connection out of room and block IP */
        public void kick(String identity) throws JsonProcessingException {
            Connection connection = findConnection(identity);
            if (connection != null){
                remove(connection);
                String[] arr = identity.split(":");
                blackSheet.add(arr[0]);
            }
        }

        /** remove blocked ip from black sheet */
        public void recover(String address){
            blackSheet.remove(address);
        }

        public void migrate(){
            for (Map.Entry<Connection, String> entry : neighbours.entrySet()) {
                String host = entry.getValue();
                migrateToHost(host);
                // migrate successfully
                if (canMigrate) break;
            }
        }

        /** migrate rooms to target host */
        public void migrateToHost(String host)  {
            /**
             first send a confirm package to client to see whether host is available
             client then send acknowledge package to server
             if all clients acknowledge then send migrate package to clients and roominfo package to new host
             otherwise find next host
             */
            canMigrate = true;
            ackToReceive = 0;
            for (Room room : rooms){
                for (Connection connection : room.getConnections()){
                    try {
                        ackToReceive++;
                        connection.sendConfirm(host);
                    } catch (JsonProcessingException e) {
                        // ignore
                    }
                }
            }
            // wait until all connect return acknowledge
            boolean wait = true;
            while(wait) {
                try {
                    sleep(500);
                    if (ackToReceive == 0) wait = false;
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            };
            if (canMigrate){
                //TODO: migrate to host
                String[] arr = host.split(":");
                try {
                    // send roominfo package to target host
                    Socket socket = new Socket(arr[0], Integer.parseInt(arr[1].trim()));
                    Connection infoConnection = new Connection(socket, true);
                    new InteractWithPeer(infoConnection).start();
                    List<String> roomInfo = getRoomInfo();
                    ackToReceive++;
                    infoConnection.sendRoomInfo(roomInfo);
                    //wait for the target host to prepare room
                    wait = true;
                    while(wait) {
                        try {
                            sleep(500);
                            if (ackToReceive == 0) wait = false;
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    infoConnection.close();
                    // send migrate package to peers
                    for (Room room : rooms){
                        for (Connection connection : room.getConnections()){
                            connection.sendMigrate(host);
                        }
                    }
                } catch (UnknownHostException e) {
                    // ignore
                } catch (JsonProcessingException e) {
                    // ignore
                } catch (IOException e) {
                    // ignore
                }
                System.out.println("All rooms migrate to " + host);
            }
        }

        public void migrateToRoom(String formerid, String roomid) throws JsonProcessingException {
            if (roomids.contains(formerid)) {
                Room former = findRoom(formerid);
                if (!roomids.contains(roomid)) {
                    if ((roomid != null) && (roomid.length() >= 3) && (roomid.length() <= 32)) {
                        // check if is AlphaNumeric and starts with alpha
                        if (roomid.matches("^[a-zA-Z][a-zA-Z0-9]*$")) {
                            createRoom(roomid);
                        }
                    }
                }
                Room room = findRoom(roomid);
                if (!formerid.equals(roomid)) {
                    for (Connection connection : former.getConnections()) {
                        connection.sendRoomChange(connection.getIdentity(), formerid, roomid);
                        for (Connection receiver : room.getConnections()) {
                            receiver.sendRoomChange(connection.getIdentity(), formerid, roomid);
                        }
                        updateConnectionRoom(connection, room);
                    }
                    rooms.remove(former);
                    roomids.remove(formerid);
                    System.out.println("Room " + formerid + " migrates to room " + roomid);
                }
            }
            client.setToPrint();
            client.printPrefix();
        }

        public void createRoom(String roomid)  {
            if (checkRoomid(roomid)) {
                Room room = new Room(roomid);
                rooms.add(room);
                roomids.add(roomid);
                if (!background) System.out.println("Room " + roomid + " is created");
            }
            if (!background) {
                client.toPrint = true;
                client.printPrefix();
            }
        }

        private boolean checkRoomid(String roomid) {
            if (roomids.contains(roomid)){
                System.out.println("Room id already in use");
                return false;
            }
            if ((roomid != null) && (roomid.length() >= 3) && (roomid.length() <= 32)) {
                // check if is AlphaNumeric and starts with alpha
                if (roomid.matches("^[a-zA-Z][a-zA-Z0-9]*$")) {
                    return true;
                }
            }
            System.out.println("Illegal room id");
            return false;
        }

        private List<String> getRoomInfo(){
            List<String> roomInfo = new ArrayList<>();
            for (Room room : rooms){
                roomInfo.add(room.getRoomid());
            }
            return roomInfo;
        }

        private List<Map<String, Object>> getRoomList(){
            List<Map<String, Object>> list = new ArrayList<>();
            for (Room r : rooms) {
                Map<String, Object> map = new HashMap<>();
                String count = r.getCount() + "";
                map.put("roomid", r.getRoomid());
                map.put("count", count);
                list.add(map);
            }
            return list;
        }

        private Room findRoom(String roomid) {
            for (Room r : rooms) {
                if (r.getRoomid().equals(roomid)) {
                    return r;
                }
            }
            return null;
        }

        private Connection findConnection(String identity){
            for (Connection connection: connections){
                if (connection.identity.equals(identity)){
                    return connection;
                }
            }
            return null;
        }

    }

    public class ParseLocalCommand extends Thread {
        public void run() {
            boolean alive = true;
            while (alive) {
                String fromUser = null;
                try {
                    fromUser = client.read();
                    if (fromUser == null) {
                        // catch Ctrl + D
                        server.close();
                        client.close();
                        System.exit(1);
                    }
                } catch (IOException e) {
                    // quit for exception
                    alive = false;
                }
                if (fromUser != null) {
                    try {
                        if (fromUser.isBlank()) {
                            // ignore null message
                            client.setToPrint();
                            //client.newLine();
                            client.printPrefix();
                            continue;
                        }
                        String[] arr = fromUser.split(" ", 3);
                        if(arr[0].matches("^(?!#).*") ){
                            // send message
                            client.setToPrint();
                            client.sendMessage(fromUser);
                        } else {
                            switch (arr[0]) {
                                case "#connect":
                                    client.setToPrint();
                                    String[] message = arr[1].split(":", 2);
                                    if(message.length < 2){
                                        System.out.println("Invalid hostname");
                                    } else {
                                        if (arr.length == 2) {
                                            String hostname = message[0];
                                            int port = Integer.parseInt(message[1].trim());
                                            client.connect(hostname, port);
                                        } else if (arr.length == 3) {
                                            int connectPort = Integer.parseInt(arr[2].trim());
                                            String hostname = message[0];
                                            int port = Integer.parseInt(message[1].trim());
                                            client.connect(hostname, port, connectPort);
                                        } else {
                                            System.out.println("Invalid connection format");
                                        }
                                    }
                                    break;
                                case "#join":
                                    client.setToPrint();
                                    if (arr.length >= 2) client.join(arr[1]);
                                    if (arr.length == 1) client.join("");
                                    break;
                                case "#who":
                                    client.setToPrint();
                                    if (arr.length >= 2) client.who(arr[1]);
                                    else {
                                        client.setToPrint();
                                        client.newLine();
                                        client.printPrefix();
                                    }
                                    break;
                                case "#list":
                                    client.setToPrint();
                                    client.list();
                                    break;
                                case "#quit":
                                    client.setToPrint();
                                    client.quit();
                                    break;
                                case "#createroom":
                                    client.setToPrint();
                                    if (arr.length == 2) server.createRoom(arr[1]);
                                    else {
                                        client.newLine();
                                        client.printPrefix();
                                    }
                                    break;
                                case "#listneighbors":
                                    client.listNeighbors();
                                    break;
                                case "#delete":
                                    if (arr.length >= 2) server.delete(arr[1]);
                                    else {
                                        client.setToPrint();
                                        client.newLine();
                                        client.printPrefix();
                                    }
                                    break;
                                case "#kick":
                                    if (arr.length == 2) server.kick(arr[1]);
                                    else {
                                        client.setToPrint();
                                        client.newLine();
                                        client.printPrefix();
                                    }
                                    break;
                                case "#searchnetwork":
                                    client.searchNetwork();
                                    break;
                                case "#migrate":
                                    if (arr.length == 3) server.migrateToRoom(arr[1], arr[2]);
                                    else{
                                        client.setToPrint();
                                        client.newLine();
                                        client.printPrefix();
                                    }
                                    break;
                                case "#help":
                                    server.help();
                                    break;
                                default:
                                    // wrong command
                                    client.setToPrint();
                                    client.newLine();
                                    client.printPrefix();
                                    break;
                            }
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    public class ListenNewConnection extends Thread {
        public void run() {
            boolean serverAlive = true;
            while (serverAlive) {
                Connection connection = null;
                try {
                    connection = server.accept();
                } catch (IOException e) {
                    serverAlive = false;
                }
                if (connection != null) new InteractWithPeer(connection).start();
            }
        }
    }

    public class InteractWithPeer extends Thread {
        private Connection connection;

        public InteractWithPeer(Connection connection){
            this.connection = connection;
        }

        public void run() {
            boolean connectionAlive = true;
            while (connectionAlive) {
                String message = null;
                try {
                    message = connection.read();
                } catch (IOException e) {
                    connectionAlive = false;
                    connection.close();
                }
                if (message != null) {
                    try {
                        JSONObject packet = JSON.parseObject(message);
                        connection.reply(packet);
                    } catch (JsonProcessingException e) {
                        connectionAlive = false;
                        connection.close();
                    }
                }
            }
        }
    }

    public void handle() throws IOException {
        InetAddress addr = InetAddress.getLocalHost();
        String hostname = addr.getHostAddress();
        this.server = new Server(PORT_P);
        this.client = new Client(hostname, PORT_I);
        client.printPrefix();
        new ListenNewConnection().start();
        new ParseLocalCommand().start();
    }

    public void doMain(String[] args) throws IOException {
        CmdLineParser parser = new CmdLineParser(this);
        try {
            parser.parseArgument(args);
        } catch (CmdLineException e) {
            System.out.println("ERROR: Unable to parse command-line options: " + e);
        }
    }

    public static void main(String[] args) {
        Peer peer = new Peer();
        // parse the arguments
        try {
            peer.doMain(args);
            peer.handle();
        } catch (IOException e) {
            System.out.println("ERROR: I/O Exception encountered: " + e);
        }
    }

}