package com.quicklink.app.link;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jayway.jsonpath.JsonPath;
import com.quicklink.app.media.MediaAsset;
import com.quicklink.app.media.MediaAssetRepository;
import com.quicklink.app.media.MediaStatus;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.redis.testcontainers.RedisContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class LinkIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    /**
     * Redis must be isolated too. Without this the test wrote click counters into the
     * shared dev Redis for links that only ever existed in the throwaway Postgres above,
     * orphaning counters whose foreign key could never be satisfied.
     */
    @Container
    @ServiceConnection
    static RedisContainer redis = new RedisContainer("redis:7");

    /**
     * Media uploads need object storage. No Testcontainers module exists for SeaweedFS,
     * so a GenericContainer does the job. Both the internal endpoint and the presigning
     * endpoint point here, so a presigned URL is reachable from this JVM.
     */
    @Container
    static GenericContainer seaweedfs = new GenericContainer("chrislusf/seaweedfs:4.53")
            .withCommand("server", "-s3", "-dir=/data")
            .withExposedPorts(8333)
            .waitingFor(org.testcontainers.containers.wait.strategy.Wait
                    .forHttp("/").forPort(8333).forStatusCode(200));

    @DynamicPropertySource
    static void s3Properties(DynamicPropertyRegistry registry) {
        String base = "http://" + seaweedfs.getHost() + ":" + seaweedfs.getMappedPort(8333);
        registry.add("app.s3.endpoint", () -> base);
        registry.add("app.s3.public-endpoint", () -> base);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    MediaAssetRepository assetRepository;

    @Autowired
    S3Client s3;

    @Autowired
    LinkRepository linkRepository;

    @Test
    void createsALinkAndRedirectsToTheTarget() throws Exception {
        String body = mockMvc.perform(post("/api/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"longUrl\":\"https://example.com/some/very/long/path\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").exists())
                .andExpect(jsonPath("$.shortUrl").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String shortCode = JsonPath.read(body, "$.shortCode");

        mockMvc.perform(get("/" + shortCode))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                                           "https://example.com/some/very/long/path"));
    }

    @Test
    void rejectsAMalformedUrl() throws Exception {
        mockMvc.perform(post("/api/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"longUrl\":\"not a url\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsNotFoundForAnUnknownCode() throws Exception {
        mockMvc.perform(get("/zzzzz"))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------------
    // Rate limiting (piece 4).
    //
    // These rely on this class having its OWN Redis container: buckets start
    // empty and cannot be polluted by (or pollute) the dev Redis. Each test
    // uses a distinct client IP so it owns its bucket outright.
    // ---------------------------------------------------------------------

    private static RequestPostProcessor fromIp(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private ResultActions createLinkFrom(String ip) throws Exception {
        return mockMvc.perform(post("/api/links")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"longUrl\":\"https://example.com/burst\"}")
                .with(fromIp(ip)));
    }

    @Test
    void allowsABurstUpToCapacityThenReturns429() throws Exception {
        for (int i = 1; i <= 10; i++) {
            createLinkFrom("10.0.0.10").andExpect(status().isCreated());
        }

        createLinkFrom("10.0.0.10")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    /** The limit is per client, not global — otherwise one user could lock out everyone. */
    @Test
    void oneClientExhaustingItsBucketDoesNotAffectAnother() throws Exception {
        for (int i = 1; i <= 10; i++) {
            createLinkFrom("10.0.0.20").andExpect(status().isCreated());
        }
        createLinkFrom("10.0.0.20").andExpect(status().isTooManyRequests());

        createLinkFrom("10.0.0.21").andExpect(status().isCreated());
    }

    /** shouldNotFilter() must keep the redirect hot path unmetered. */
    @Test
    void redirectsAreNeverRateLimited() throws Exception {
        String body = createLinkFrom("10.0.0.30")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String shortCode = JsonPath.read(body, "$.shortCode");

        for (int i = 1; i <= 25; i++) {
            mockMvc.perform(get("/" + shortCode).with(fromIp("10.0.0.30")))
                   .andExpect(status().isFound());
        }
    }

    // ---------------------------------------------------------------------
    // Media links (Stage 4f). No worker runs in this test, so an upload stays
    // PENDING — which is exactly what makes the 202 branch testable here.
    // ---------------------------------------------------------------------

    private static byte[] png(int size) throws IOException {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                image.setRGB(x, y, (((x * 7) % 256) << 16) | (((y * 5) % 256) << 8) | ((x + y) % 256));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private String uploadMedia() throws Exception {
        String body = mockMvc.perform(multipart("/api/media")
                        .file(new MockMultipartFile("file", "pic.png", "image/png", png(64))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.shortCode");
    }

    private MediaAsset assetFor(String shortCode) {
        Link link = linkRepository.findByShortCode(shortCode).orElseThrow();
        return assetRepository.findById(link.getMediaAssetId()).orElseThrow();
    }

    @Test
    void uploadReturns202AndTheLinkIsNotYetServable() throws Exception {
        String shortCode = uploadMedia();

        mockMvc.perform(get("/" + shortCode))
               .andExpect(status().isAccepted());   // still PENDING, come back later

        mockMvc.perform(get("/api/media/" + shortCode + "/status"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.status").value("PENDING"))
               .andExpect(jsonPath("$.processedBytes").doesNotExist());
    }

    @Test
    void aReadyMediaLinkRedirectsToAWorkingPresignedUrl() throws Exception {
        String shortCode = uploadMedia();

        // Stand in for the worker: publish a processed object and flip the status.
        MediaAsset asset = assetFor(shortCode);
        byte[] processed = png(32);
        String processedKey = "processed-" + asset.getId();
        s3.putObject(b -> b.bucket("processed").key(processedKey).contentType("image/png"),
                RequestBody.fromBytes(processed));
        asset.setProcessedKey(processedKey);
        asset.setProcessedBytes((long) processed.length);
        asset.setStatus(MediaStatus.READY);
        assetRepository.save(asset);

        String location = mockMvc.perform(get("/" + shortCode))
                .andExpect(status().isFound())
                .andReturn().getResponse().getHeader("Location");

        assertTrue(location != null && location.contains("X-Amz-Signature"),
                   "must redirect to a presigned URL, got: " + location);

        // The redirect is only useful if the URL actually serves the bytes.
        HttpResponse<byte[]> fetched = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create(location)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, fetched.statusCode());
        assertEquals(processed.length, fetched.body().length);
    }

    /** 410, not 202: a failed asset will never become ready, so do not invite polling. */
    @Test
    void aFailedMediaLinkReturns410Gone() throws Exception {
        String shortCode = uploadMedia();

        MediaAsset asset = assetFor(shortCode);
        asset.setStatus(MediaStatus.FAILED);
        asset.setErrorMessage("not a decodable image");
        assetRepository.save(asset);

        mockMvc.perform(get("/" + shortCode))
               .andExpect(status().isGone());

        mockMvc.perform(get("/api/media/" + shortCode + "/status"))
               .andExpect(jsonPath("$.status").value("FAILED"))
               .andExpect(jsonPath("$.errorMessage").value("not a decodable image"));
    }

    @Test
    void statusEndpointRejectsAUrlLink() throws Exception {
        String body = mockMvc.perform(post("/api/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"longUrl\":\"https://example.com/plain\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String shortCode = JsonPath.read(body, "$.shortCode");

        mockMvc.perform(get("/api/media/" + shortCode + "/status"))
               .andExpect(status().isNotFound());
    }

    /**
     * In compose the s3-init container creates these; nothing does so here. A missing
     * bucket surfaces as 403 Access Denied from SeaweedFS, not 404, which is a
     * misleading error to debug. Idempotent because the container is shared by every
     * test in this class.
     */
    @BeforeEach
    void createBuckets() {
        for (String bucket : new String[] {"originals", "processed"}) {
            try {
                s3.createBucket(b -> b.bucket(bucket));
            } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException alreadyThere) {
                // a previous test in this class created it
            }
        }
    }
}
