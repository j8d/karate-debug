package com.j8d.karate.intellij.licensing;

import com.intellij.openapi.diagnostic.Logger;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.QueryStringDecoder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.ide.RestService;

import java.util.List;
import java.util.Map;

/**
 * REST service handler for OAuth callback.
 * Listens on the built-in IDE HTTP server at /api/karate-debug/auth/callback
 * 
 * Example callback URL: http://localhost:63342/api/karate-debug/auth/callback?code=xxx
 */
public class KarateAuthRestService extends RestService {

    private static final Logger LOG = Logger.getInstance(KarateAuthRestService.class);
    private static final String SERVICE_NAME = "karate-debug/auth/callback";

    @NotNull
    @Override
    protected String getServiceName() {
        return SERVICE_NAME;
    }

    @Override
    protected boolean isMethodSupported(@NotNull HttpMethod method) {
        return method == HttpMethod.GET;
    }

    @Override
    protected boolean isHostTrusted(@NotNull FullHttpRequest request,
                                    @NotNull QueryStringDecoder urlDecoder) {
        // Allow callbacks from localhost (browser redirects)
        return true;
    }

    @Nullable
    @Override
    public String execute(@NotNull QueryStringDecoder urlDecoder,
                          @NotNull FullHttpRequest request,
                          @NotNull ChannelHandlerContext context) {
        LOG.info("Received OAuth callback");

        Map<String, List<String>> params = urlDecoder.parameters();
        List<String> codeParams = params.get("code");
        String code = (codeParams != null && !codeParams.isEmpty()) ? codeParams.get(0) : null;

        if (code != null) {
            LOG.info("Auth code received, completing login");
            LicenseManager.getInstance().completeGitHubLogin(code);

            // Return success HTML page
            String html = "<!DOCTYPE html><html><head>" +
                    "<title>Authentication Successful</title>" +
                    "<style>body{font-family:sans-serif;text-align:center;padding:50px;}" +
                    "h1{color:#2ecc71;}</style></head><body>" +
                    "<h1>&#10004; Authentication Successful</h1>" +
                    "<p>You can close this window and return to IntelliJ IDEA.</p>" +
                    "<script>setTimeout(function(){window.close();},2000);</script>" +
                    "</body></html>";
            sendResponse(request, context, html);
        } else {
            LOG.warn("No code in callback");
            String html = "<!DOCTYPE html><html><head>" +
                    "<title>Authentication Failed</title>" +
                    "<style>body{font-family:sans-serif;text-align:center;padding:50px;}" +
                    "h1{color:#e74c3c;}</style></head><body>" +
                    "<h1>&#10008; Authentication Failed</h1>" +
                    "<p>No authorization code received. Please try again.</p>" +
                    "</body></html>";
            sendResponse(request, context, html);
        }

        return null;
    }

    private void sendResponse(FullHttpRequest request, ChannelHandlerContext context, String html) {
        try {
            sendResponse(request, context, html, "text/html");
        } catch (Exception e) {
            LOG.error("Failed to send response", e);
        }
    }

    private void sendResponse(FullHttpRequest request, ChannelHandlerContext context, 
                              String html, String contentType) {
        try {
            io.netty.buffer.ByteBuf content = io.netty.buffer.Unpooled.wrappedBuffer(
                    html.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            io.netty.handler.codec.http.FullHttpResponse response = 
                    new io.netty.handler.codec.http.DefaultFullHttpResponse(
                            io.netty.handler.codec.http.HttpVersion.HTTP_1_1,
                            io.netty.handler.codec.http.HttpResponseStatus.OK,
                            content);
            response.headers().set("Content-Type", contentType + "; charset=utf-8");
            response.headers().set("Content-Length", content.readableBytes());
            context.writeAndFlush(response);
        } catch (Exception e) {
            LOG.error("Failed to send response", e);
        }
    }
}

