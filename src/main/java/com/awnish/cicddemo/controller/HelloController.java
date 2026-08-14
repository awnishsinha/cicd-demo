package com.awnish.cicddemo.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {

    @GetMapping("/api/hello")
    public String hello(){
        return "Hello Version-Blue from CI/CD Version 2";
    }

    @GetMapping("/api/welcome")
    public String welcome(){
        return "Welcome to Version-Blue from CI/CD Version 2";
    }
}
