package com.diet.auth;

@FunctionalInterface
public interface WechatCodeExchangeClient {
    String exchange(String code);
}
