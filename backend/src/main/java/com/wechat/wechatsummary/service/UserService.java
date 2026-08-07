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

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

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
        return user.getId();
    }

    public String login(String username, String rawPassword) {
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new InvalidCredentialsException("Invalid username or password"));

        if (!passwordEncoder.matches(rawPassword, user.getPassword())) {
            throw new InvalidCredentialsException("Invalid username or password");
        }

        return user.getId();
    }
}