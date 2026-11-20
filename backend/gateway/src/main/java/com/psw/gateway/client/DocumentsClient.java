package com.psw.gateway.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "documents", url = "${routes.documents}")
public interface DocumentsClient {
    @PostMapping(value = "/documents", consumes = MediaType.APPLICATION_JSON_VALUE)
    String createDocument (@RequestBody String payload);

    @GetMapping("/documents/{id}")
    String getDocumentById (@PathVariable ("id") int id);
}
