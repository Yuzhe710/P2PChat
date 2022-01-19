package com.chatroom.base;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class Packets {

    @JsonTypeInfo(
            use = JsonTypeInfo.Id.NAME,
            property = "type"
    )
    @JsonSubTypes({
            @JsonSubTypes.Type(value = Join.class, name = "join"),
            @JsonSubTypes.Type(value = Who.class, name = "who"),
            @JsonSubTypes.Type(value = IdentityList.class, name = "list"),
            @JsonSubTypes.Type(value = ListNeighbors.class, name = "listneighbors"),
            @JsonSubTypes.Type(value = HostChange.class, name = "hostchange"),
            @JsonSubTypes.Type(value = ClientMessage.class, name = "message"),
            @JsonSubTypes.Type(value = Quit.class, name = "quit"),
            @JsonSubTypes.Type(value = Acknowledge.class, name = "acknowledge"),
            @JsonSubTypes.Type(value = FitRoom.class, name = "fitroom")
    })

    public static class ToServer {
    }

    @JsonTypeName("join")
    public static class Join extends ToServer {
        public String roomid;
        public Join set(String roomid){
            this.roomid = roomid;
            return this;
        }

    }

    @JsonTypeName("who")
    public static class Who extends ToServer {
        public String roomid;
        public Who set(String roomid){
            this.roomid = roomid;
            return this;
        }
    }

    @JsonTypeName("list")
    public static class IdentityList extends ToServer {
    }

    @JsonTypeName("listneighbors")
    public static class ListNeighbors extends ToServer {
    }

    @JsonTypeName("hostchange")
    public static class HostChange extends ToServer {
        public String host;
        public HostChange set(String host){
            this.host = host;
            return this;
        }
    }

    @JsonTypeName("message")
    public static class ClientMessage extends ToServer {
        public String content;
        public ClientMessage set(String content){
            this.content = content;
            return this;
        }
    }

    @JsonTypeName("quit")
    public static class Quit extends ToServer {
    }

    @JsonTypeName("acknowledge")
    public static class Acknowledge extends ToServer {
        public boolean available ;
        public Acknowledge set(boolean available){
            this.available = available;
            return this;
        }
    }

    @JsonTypeName("fitroom")
    public static class FitRoom extends ToServer {
    }


    @JsonTypeInfo(
            use = JsonTypeInfo.Id.NAME,
            property = "type"
    )
    @JsonSubTypes({
            @JsonSubTypes.Type(value = RoomChange.class, name = "roomchange"),
            @JsonSubTypes.Type(value = RoomContents.class, name = "roomcontents"),
            @JsonSubTypes.Type(value = RoomList.class, name = "roomlist"),
            @JsonSubTypes.Type(value = ServerMessage.class, name = "message"),
            @JsonSubTypes.Type(value = Neighbors.class, name = "neighbors"),
            @JsonSubTypes.Type(value = Confirm.class, name = "confirm"),
            @JsonSubTypes.Type(value = Migrate.class, name = "migrate"),
            @JsonSubTypes.Type(value = RoomInfo.class, name = "roominfo")
    })

    public static class ToClient{
    }

    @JsonTypeName("roomchange")
    public static class RoomChange extends ToClient{
        public String identity, former, roomid;
        public RoomChange set(String identity, String former, String roomid){
            this.identity = identity;
            this.former = former;
            this.roomid = roomid;
            return this;
        }
    }

    @JsonTypeName("roomcontents")
    public static class RoomContents extends ToClient{
        public String roomid;
        public List<String> identities;
        public RoomContents set(String roomid, List<String> identities){
            this.roomid = roomid;
            this.identities = identities;
            return this;
        }
    }

    @JsonTypeName("roomlist")
    public static class RoomList extends ToClient {
        public List<Map<String, Object>> rooms = new ArrayList<>();
        public RoomList set(List<Map<String, Object>> list) {
            this.rooms = list;
            return this;
        }
    }

    @JsonTypeName("neighbors")
    public static class Neighbors extends ToClient{
        public List<String> neighbors;
        public Neighbors set(List<String> neighbors){
            this.neighbors = neighbors;
            return this;
        }
    }

    @JsonTypeName("message")
    public static class ServerMessage extends ToClient{
        public String identity, content;
        public ServerMessage set(String identity, String content){
            this.identity = identity;
            this.content = content;
            return this;
        }
    }

    @JsonTypeName("confirm")
    public static class Confirm extends ToClient{
        public String host;
        public Confirm set(String host){
            this.host = host;
            return this;
        }
    }

    @JsonTypeName("migrate")
    public static class Migrate extends ToClient{
        public String host;
        public Migrate set(String host){
            this.host = host;
            return this;
        }
    }

    @JsonTypeName("roominfo")
    public static class RoomInfo extends ToClient{
        public List<String> roomInfo = new ArrayList<>();
        public RoomInfo set(List<String> roomInfo){
            this.roomInfo = roomInfo;
            return this;
        }
    }
}

