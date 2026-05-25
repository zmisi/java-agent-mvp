package com.example.javaagentmvp;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({ChatMemoryProperties.class, com.example.javaagentmvp.dbagent.DbAgentProperties.class})
@MapperScan({
        "com.example.javaagentmvp.chat.persistence.mapper",
        "com.example.javaagentmvp.dbagent.persistence.mapper"
})
public class JavaAgentMvpApplication {

    public static void main(String[] args) {
        SpringApplication.run(JavaAgentMvpApplication.class, args);
    }
}
