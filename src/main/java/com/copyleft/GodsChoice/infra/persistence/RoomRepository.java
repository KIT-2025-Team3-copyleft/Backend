package com.copyleft.GodsChoice.infra.persistence;

import com.copyleft.GodsChoice.domain.Room;
import com.copyleft.GodsChoice.global.constant.RedisKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Repository
@RequiredArgsConstructor
public class RoomRepository {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper; // Room 객체 변환용

    public void saveRoom(Room room) {
        String key = RedisKey.ROOM.makeKey(room.getRoomId());

        redisTemplate.opsForValue().set(key, room);
        redisTemplate.expire(key, 1, TimeUnit.HOURS);
    }

    public Optional<Room> findRoomById(String roomId) {
        String key = RedisKey.ROOM.makeKey(roomId);

        Object obj = redisTemplate.opsForValue().get(key);
        if (obj instanceof Room) {
            return Optional.of((Room) obj);
        }
        return Optional.ofNullable(objectMapper.convertValue(obj, Room.class));
    }

    public String findRoomIdByCode(String roomCode) {
        String key = RedisKey.ROOM_CODE.makeKey(roomCode);

        Object result = redisTemplate.opsForValue().get(key);
        return result != null ? result.toString() : null;
    }

    public void saveRoomCodeMapping(String roomCode, String roomId) {
        String key = RedisKey.ROOM_CODE.makeKey(roomCode);

        redisTemplate.opsForValue().set(key, roomId, 1, TimeUnit.HOURS);
    }

    public void addWaitingRoom(String roomId) {
        redisTemplate.opsForSet().add(RedisKey.WAITING_ROOMS.getKey(), roomId);
    }

    public void removeWaitingRoom(String roomId) {
        redisTemplate.opsForSet().remove(RedisKey.WAITING_ROOMS.getKey(), roomId);
    }

    public String getRandomWaitingRoomId() {
        Object roomId = redisTemplate.opsForSet().randomMember(RedisKey.WAITING_ROOMS.getKey());
        return roomId != null ? roomId.toString() : null;
    }

    public void deleteRoom(String roomId, String roomCode) {
        redisTemplate.delete(RedisKey.ROOM.makeKey(roomId));
        redisTemplate.delete(RedisKey.ROOM_CODE.makeKey(roomCode));
        removeWaitingRoom(roomId);
    }

    public void saveSessionRoomMapping(String sessionId, String roomId) {
        redisTemplate.opsForValue().set(RedisKey.SESSION_ROOM.makeKey(sessionId), roomId);
    }

    public String getRoomIdBySessionId(String sessionId) {
        Object result = redisTemplate.opsForValue().get(RedisKey.SESSION_ROOM.makeKey(sessionId));
        return result != null ? result.toString() : null;
    }

    public void deleteSessionRoomMapping(String sessionId) {
        redisTemplate.delete(RedisKey.SESSION_ROOM.makeKey(sessionId));
    }
}