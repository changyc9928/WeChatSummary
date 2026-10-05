package com.wechat.wechatsummary.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.wechat.wechatsummary.entity.User;
import com.wechat.wechatsummary.exception.BusinessException;
import com.wechat.wechatsummary.exception.InvalidCredentialsException;
import com.wechat.wechatsummary.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private UserTokenService userTokenService;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, passwordEncoder, userTokenService);
    }

    @Test
    void registerHashesPasswordSavesUserAndIssuesToken() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("secret")).thenReturn("hashed");
        when(userTokenService.issue(any())).thenReturn("token-1");

        String token = userService.register("alice", "secret");

        assertEquals("token-1", token);
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertEquals("alice", saved.getValue().getUsername());
        assertEquals("hashed", saved.getValue().getPassword());
        verify(userTokenService).issue(saved.getValue().getId());
    }

    @Test
    void registerRejectsDuplicateUsername() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(new User()));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> userService.register("alice", "secret"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(userRepository, never()).save(any());
        verify(userTokenService, never()).issue(any());
    }

    @Test
    void loginIssuesTokenWhenPasswordMatches() {
        User user = new User();
        user.setId("user-1");
        user.setPassword("hashed");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("secret", "hashed")).thenReturn(true);
        when(userTokenService.issue("user-1")).thenReturn("token-1");

        assertEquals("token-1", userService.login("alice", "secret"));
    }

    @Test
    void loginRejectsWrongPasswordWithoutIssuingToken() {
        User user = new User();
        user.setId("user-1");
        user.setPassword("hashed");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        assertThrows(InvalidCredentialsException.class,
            () -> userService.login("alice", "wrong"));
        verify(userTokenService, never()).issue(any());
    }

    @Test
    void loginRejectsUnknownUsername() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThrows(InvalidCredentialsException.class,
            () -> userService.login("ghost", "secret"));
    }

    @Test
    void logoutRevokesThePresentedToken() {
        userService.logout("token-1");

        verify(userTokenService).revoke("token-1");
    }
}
