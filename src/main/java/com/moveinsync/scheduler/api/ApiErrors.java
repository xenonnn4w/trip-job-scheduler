package com.moveinsync.scheduler.api;

import java.time.DateTimeException;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler({IllegalArgumentException.class,DateTimeException.class,MethodArgumentNotValidException.class})
    ResponseEntity<Map<String,String>> badRequest(Exception e) { return ResponseEntity.badRequest().body(Map.of("error","Invalid request")); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String,String>> conflict(Exception e) { return ResponseEntity.status(409).body(Map.of("error","Resource conflict")); }
}
