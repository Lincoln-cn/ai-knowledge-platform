package cn.lincoln.aiplatform.identity.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/** 已认证但无权限时的出口（官方扩展点）。 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

  @Override
  public void handle(
      HttpServletRequest request, HttpServletResponse response, AccessDeniedException cause)
      throws IOException {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    boolean authenticated = auth != null && auth.isAuthenticated();
    ErrorResponses.forbidden(response, authenticated);
  }
}
