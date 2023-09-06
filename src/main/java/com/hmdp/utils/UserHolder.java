package com.hmdp.utils;

import com.hmdp.dto.UserDTO;

/**
 * 用户信息存储，ThreadLocal保证线程安全
 */
public class UserHolder {
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();

    public static void saveUser(UserDTO user) {
        tl.set(user);
    }

    public static UserDTO getUser() {
        return tl.get();
    }

    public static void removeUser() {
        tl.remove();
    }
}
