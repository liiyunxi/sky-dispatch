package com.skydispatch.auth;

import com.skydispatch.common.ApiResponse;
import com.skydispatch.common.BusinessException;
import com.skydispatch.common.ErrorCode;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录接口：内部演示平台的唯一认证入口。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@RequestBody LoginRequest request) {
        if (request == null || request.username() == null || request.password() == null) {
            throw BusinessException.invalidParam("用户名和密码不能为空");
        }
        AuthService.LoginResult result = authService.login(
                request.username().trim(), request.password());
        if (result == null) {
            // 不区分"用户名不存在"和"密码错误"，避免账号枚举
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        return ApiResponse.ok(new LoginResponse(
                result.token(), result.username(), result.expiresAtEpochSeconds()));
    }

    public record LoginRequest(String username, String password) {
    }

    /** 前端只依赖 token 与过期时间；username 用于右上角展示。 */
    public record LoginResponse(String token, String username, long expiresAt) {
    }
}
