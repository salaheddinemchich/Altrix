package com.example.altrix;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

/**
 * JAX-RS application root — exposes all resources under {@code /api}.
 */
@ApplicationPath("/api")
public class JaxRsApplication extends Application {
}
