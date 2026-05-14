package ai.causa;

import ai.causa.svc.AllocatorService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/alloc")
public class HeapOOM {

    @Inject
    AllocatorService svc;

    @GET
    @Path("/hit")
    @Produces(MediaType.APPLICATION_JSON)
    public AllocatorService.Result hit() {
        return svc.onRequest();
    }

    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public AllocatorService.Status status() {
        return svc.status();
    }
}
