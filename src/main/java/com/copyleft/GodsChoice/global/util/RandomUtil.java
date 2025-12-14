package com.copyleft.GodsChoice.global.util;

import java.security.SecureRandom;
import java.util.UUID;

public class RandomUtil {

    private static final SecureRandom random = new SecureRandom();
    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    /**
     * UUID 생성 (RoomId용)
     */
    public static String generateRoomId() {
        return UUID.randomUUID().toString();
    }

    /**
     * 대문자+숫자 6자리 코드 생성 (RoomCode용)
     */
    public static String generateRoomCode() {
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            int index = random.nextInt(ALPHANUMERIC.length());
            sb.append(ALPHANUMERIC.charAt(index));
        }
        return sb.toString();
    }

    public static int generateRandomHp(int min, int max) {
        if (min > max) {
            throw new IllegalArgumentException("Max must be greater than Min");
        }
        return random.nextInt(max - min + 1) + min;
    }
}