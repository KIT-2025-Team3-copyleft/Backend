package com.copyleft.GodsChoice.game.repository;

import com.copyleft.GodsChoice.domain.Room;
import com.copyleft.GodsChoice.global.constant.RedisKey;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class RoomRepository {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper; // Room 객체 변환용

    private static final long ROOM_TTL_HOURS = 1L;

    public void saveRoom(Room room) {
        String key = RedisKey.ROOM.makeKey(room.getRoomId());

        try {
            String roomJson = objectMapper.writeValueAsString(room);
            redisTemplate.opsForValue().set(key, roomJson, ROOM_TTL_HOURS, TimeUnit.HOURS);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Room Save Error", e);
        }
    }

    public Optional<Room> findRoomById(String roomId) {
        String key = RedisKey.ROOM.makeKey(roomId);
        String roomJson = redisTemplate.opsForValue().get(key);

        if (roomJson == null) return Optional.empty();

        try {
            Room room = objectMapper.readValue(roomJson, Room.class);
            return Optional.of(room);
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    public String findRoomIdByCode(String roomCode) {
        String key = RedisKey.ROOM_CODE.makeKey(roomCode);
        return redisTemplate.opsForValue().get(key);
    }

    public void saveRoomCodeMapping(String roomCode, String roomId) {
        String key = RedisKey.ROOM_CODE.makeKey(roomCode);
        redisTemplate.opsForValue().set(key, roomId, 1, TimeUnit.HOURS);
    }

    public boolean saveRoomCodeMappingIfAbsent(String roomCode, String roomId) {
        String key = RedisKey.ROOM_CODE.makeKey(roomCode);
        Boolean result = redisTemplate.opsForValue().setIfAbsent(key, roomId, ROOM_TTL_HOURS, TimeUnit.HOURS);
        return Boolean.TRUE.equals(result);
    }

    public void addWaitingRoom(String roomId) {
        redisTemplate.opsForSet().add(RedisKey.WAITING_ROOMS.getKey(), roomId);
    }

    public void removeWaitingRoom(String roomId) {
        redisTemplate.opsForSet().remove(RedisKey.WAITING_ROOMS.getKey(), roomId);
    }

    public List<Room> findAllWaitingRooms() {
        Set<String> roomIds = redisTemplate.opsForSet().members(RedisKey.WAITING_ROOMS.getKey());

        if (roomIds == null || roomIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> keys = roomIds.stream()
                .map(RedisKey.ROOM::makeKey)
                .collect(Collectors.toList());

        List<String> jsonList = redisTemplate.opsForValue().multiGet(keys);

        if (jsonList == null) return Collections.emptyList();

        List<Room> rooms = new ArrayList<>();
        for (String json : jsonList) {
            if (json != null) {
                try {
                    rooms.add(objectMapper.readValue(json, Room.class));
                } catch (JsonProcessingException e) {
                    // 파싱 에러 난 방은 무시
                }
            }
        }
        return rooms;
    }

    public void deleteRoom(String roomId, String roomCode) {
        redisTemplate.delete(RedisKey.ROOM.makeKey(roomId));
        redisTemplate.delete(RedisKey.ROOM_CODE.makeKey(roomCode));
        removeWaitingRoom(roomId);
    }

    public void saveSessionRoomMapping(String sessionId, String roomId) {
        redisTemplate.opsForValue().set(
                RedisKey.SESSION_ROOM.makeKey(sessionId),
                roomId,
                ROOM_TTL_HOURS, TimeUnit.HOURS
        );
    }

    public String getRoomIdBySessionId(String sessionId) {
        return redisTemplate.opsForValue().get(RedisKey.SESSION_ROOM.makeKey(sessionId));
    }

    public void deleteSessionRoomMapping(String sessionId) {
        redisTemplate.delete(RedisKey.SESSION_ROOM.makeKey(sessionId));
    }
}