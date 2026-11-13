package com.psw.gateway.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "documents", url = "${routes.documents}")
public interface DocumentsClient {
    @PostMapping("/documents")
    String createDocument (@RequestBody String payload);
}
