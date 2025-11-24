package com.copyleft.GodsChoice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {

        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("Async-Log-");
        executor.setVirtualThreads(true);

        return executor;
    }
}