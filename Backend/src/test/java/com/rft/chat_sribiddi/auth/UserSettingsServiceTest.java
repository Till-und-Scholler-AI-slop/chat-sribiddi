package com.rft.chat_sribiddi.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserSettingsServiceTest {

  private static final String USER_ID = "user-123";

  @Mock private UserRepository userRepository;

  @Test
  void saveBillingModePreference_persistsByokForFreeUser() {
    User user = new User();
    user.setProviderId(USER_ID);
    user.setPlan(Plan.FREE);
    when(userRepository.findByProviderId(USER_ID)).thenReturn(Optional.of(user));

    UserSettingsService service = new UserSettingsService(userRepository);

    BillingMode updated = service.saveBillingModePreference(USER_ID, BillingMode.BYOK);

    assertThat(updated).isEqualTo(BillingMode.BYOK);
    assertThat(user.getBillingModePreference()).isEqualTo(BillingMode.BYOK);
    verify(userRepository).save(user);
  }

  @Test
  void saveBillingModePreference_persistsManagedForPremiumUser() {
    User user = new User();
    user.setProviderId(USER_ID);
    user.setPlan(Plan.PREMIUM);
    when(userRepository.findByProviderId(USER_ID)).thenReturn(Optional.of(user));

    UserSettingsService service = new UserSettingsService(userRepository);

    BillingMode updated = service.saveBillingModePreference(USER_ID, BillingMode.MANAGED);

    assertThat(updated).isEqualTo(BillingMode.MANAGED);
    assertThat(user.getBillingModePreference()).isEqualTo(BillingMode.MANAGED);
    verify(userRepository).save(user);
  }

  @Test
  void saveBillingModePreference_rejectsManagedForFreeUser() {
    User user = new User();
    user.setProviderId(USER_ID);
    user.setPlan(Plan.FREE);
    when(userRepository.findByProviderId(USER_ID)).thenReturn(Optional.of(user));

    UserSettingsService service = new UserSettingsService(userRepository);

    assertThatThrownBy(() -> service.saveBillingModePreference(USER_ID, BillingMode.MANAGED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Premium or Max");
  }

  @Test
  void saveBillingModePreference_createsMissingUserForNonManagedMode() {
    when(userRepository.findByProviderId(USER_ID)).thenReturn(Optional.empty());
    when(userRepository.save(org.mockito.ArgumentMatchers.any(User.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    UserSettingsService service = new UserSettingsService(userRepository);
    ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);

    service.saveBillingModePreference(USER_ID, BillingMode.AUTO);

    verify(userRepository, org.mockito.Mockito.times(2)).save(captor.capture());
    assertThat(captor.getAllValues().getLast().getProviderId()).isEqualTo(USER_ID);
    assertThat(captor.getAllValues().getLast().getBillingModePreference()).isEqualTo(BillingMode.AUTO);
  }
}
