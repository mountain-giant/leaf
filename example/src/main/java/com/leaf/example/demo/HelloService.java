package com.leaf.example.demo;

/**
 * @author yefei
 * @date 2017-06-20 14:13
 */
public interface HelloService {

    String sayHello(String name);

    String sayHello(String name, String age);

    String sayHello(User user);

}
