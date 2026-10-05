package com.wechat.wechatsummary.service;

import com.wechat.wechatsummary.entity.User;
import com.wechat.wechatsummary.exception.BusinessException;
import com.wechat.wechatsummary.exception.InvalidCredentialsException;
import com.wechat.wechatsummary.repository.UserRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Manages user credentials (registration, password verification) and issues Bearer tokens for
 * authenticated users. Tokens are opaque, stored in Redis by {@link UserTokenService}, and carry
 * the user UUID entirely server-side.
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserTokenService userTokenService;

    /**
     * Registers a user and immediately issues a Bearer token (auto-login).
     *
     * @param username    unique username
     * @param rawPassword plain-text password, hashed before storage
     * @return the Bearer token for the newly created user
     */
    public String register(String username, String rawPassword) {
        userRepository.findByUsername(username)
            .ifPresent(existing -> {
                throw new BusinessException(HttpStatus.CONFLICT, "Username already exists");
            });

        User user = new User();
        user.setId(UUID.randomUUID().toString()); // Set UUID as primary key
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(rawPassword));

        userRepository.save(user);
        return userTokenService.issue(user.getId());
    }

    /**
     * Verifies credentials and issues a fresh Bearer token.
     *
     * @return the Bearer token for the authenticated user
     * @throws InvalidCredentialsException when the username is unknown or the password is wrong
     */
    public String login(String username, String rawPassword) {
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new InvalidCredentialsException("Invalid username or password"));

        if (!passwordEncoder.matches(rawPassword, user.getPassword())) {
            throw new InvalidCredentialsException("Invalid username or password");
        }

        return userTokenService.issue(user.getId());
    }

    /** Revokes the presented token (logout). */
    public void logout(String token) {
        userTokenService.revoke(token);
    }
}
