package com.example.altrix.order;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.util.Collection;

@Path("/orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrderResource {

    @Inject
    OrderService service;

    @POST
    public Response create(CreateOrderRequest req) {
        Order order = service.createOrder(req.customerEmail(), req.product(), req.quantity(), req.total());
        return Response.status(Response.Status.CREATED).entity(order).build();
    }

    @GET
    public Collection<Order> list() {
        return service.findAll();
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") String id) {
        return service.findById(id)
                .map(o -> Response.ok(o).build())
                .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    @DELETE
    @Path("/{id}")
    public Response cancel(@PathParam("id") String id) {
        return service.cancel(id)
                ? Response.noContent().build()
                : Response.status(Response.Status.NOT_FOUND).build();
    }

    public record CreateOrderRequest(String customerEmail, String product, int quantity, BigDecimal total) {}
}
