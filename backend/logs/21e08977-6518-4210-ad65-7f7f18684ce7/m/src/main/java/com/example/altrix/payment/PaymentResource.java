package com.example.altrix.payment;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

@Path("/payments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PaymentResource {

    @Inject
    PaymentService service;

    @POST
    public Response create(CreatePaymentRequest req) {
        Payment payment = service.recordPayment(req.orderId(), req.amount(), req.method());
        return Response.status(Response.Status.CREATED).entity(payment).build();
    }

    @GET
    public Collection<Payment> list() {
        return service.findAll();
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") String id) {
        return service.findById(id)
                .map(p -> Response.ok(p).build())
                .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    @GET
    @Path("/order/{orderId}")
    public List<Payment> byOrder(@PathParam("orderId") String orderId) {
        return service.findByOrderId(orderId);
    }

    public record CreatePaymentRequest(String orderId, BigDecimal amount, PaymentMethod method) {}
}
