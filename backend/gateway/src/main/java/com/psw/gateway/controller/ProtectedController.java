package com.psw.gateway.controller;

import com.psw.gateway.client.DocumentsClient;
import com.psw.gateway.model.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/app")
public class ProtectedController {
    private final DocumentsClient documentsClient;

    public ProtectedController (DocumentsClient documentsClient) {
        this.documentsClient = documentsClient;
    }
    @PostMapping("/documents")
    public void createDocument (@RequestBody String payload,
                                HttpServletRequest request){
        RequestContext ctx = ctxHandler(request);
        ctx.setResponseBody(documentsClient.createDocument(payload));
        ctx.setStatus(201);
    }

    @GetMapping("/documents/{id}")
    public void getDocument (@PathVariable ("id") int id,
                             HttpServletRequest request){
        RequestContext ctx = ctxHandler(request);
        ctx.setResponseBody(documentsClient.getDocumentById(id));
        ctx.setStatus(200);
    }

    @GetMapping("/health-check")
    public void healthCheck (HttpServletRequest request){
        RequestContext ctx = ctxHandler(request);
        ctx.setStatus(204);
    }

    private RequestContext ctxHandler (HttpServletRequest request){
        RequestContext ctx = (RequestContext) request.getAttribute("context");
        if (ctx == null)
            throw new RuntimeException("Request context failure"); //TODO: add logging mechanism
        return ctx;
    }
}
