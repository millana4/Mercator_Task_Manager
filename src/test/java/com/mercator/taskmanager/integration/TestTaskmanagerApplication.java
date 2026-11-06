package com.mercator.taskmanager.integration;

import com.mercator.taskmanager.TaskmanagerApplication;

import org.springframework.boot.SpringApplication;

public class TestTaskmanagerApplication {

	public static void main(String[] args) {
		SpringApplication.from(TaskmanagerApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
