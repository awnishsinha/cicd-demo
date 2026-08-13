package com.awnish.cicddemo.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {

    @GetMapping("/api/hello")
    public String hello(){
        return "Hello Version-Green from CI/CD";
    }

    @GetMapping("/api/welcome")
    public String welcome(){
        return "Welcome to Version-Green from CI/CD";
    }
}
