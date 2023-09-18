package com.hmdp;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;


@EnableAspectJAutoProxy(exposeProxy = true)//暴露Spring代理对象
@MapperScan("com.hmdp.mapper")
@SpringBootApplication
public class HmDianPingApplication {

    public static void main(String[] args) {
        SpringApplication.run(HmDianPingApplication.class, args);
        System.out.println("   _                            _        _____                             \n" +
                " | |                          | |      / ____|                            \n" +
                " | |     __ _ _ __  _   _  ___| |__   | (___  _   _  ___ ___ ___  ___ ___ \n" +
                " | |    / _` | '_ \\| | | |/ __| '_ \\   \\___ \\| | | |/ __/ __/ _ \\/ __/ __|\n" +
                " | |___| (_| | | | | |_| | (__| | | |  ____) | |_| | (_| (_|  __/\\__ \\__ \\\n" +
                " |______\\__,_|_| |_|\\__,_|\\___|_| |_| |_____/ \\__,_|\\___\\___\\___||___/___/\n" +
                "                                                                          \n" +
                "                                                                          \n" +
                "\n");
        System.out.println("项目启动成功！");
    }

}
