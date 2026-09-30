package org.signal.registration.sender;

import com.google.i18n.phonenumbers.Phonenumber;
import org.signal.registration.util.Strings;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;

public class TestVerificationCodeSender implements VerificationCodeSender {

  private final String name;
  private final List<String> supportedLanguages;

  public TestVerificationCodeSender(
      final String name,
      final List<String> supportedLangauges
  ) {
    this.name = name;
    this.supportedLanguages = supportedLangauges;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public Duration getAttemptTtl() {
    return null;
  }

  @Override
  public boolean supportsTransport(final MessageTransport transport) {
    return MessageTransport.SMS == transport;
  }

  @Override
  public boolean supportsLanguage(
      final MessageTransport messageTransport,
      final Phonenumber.PhoneNumber phoneNumber,
      final List<Locale.LanguageRange> languageRanges) {
    return Locale.lookupTag(languageRanges, supportedLanguages) != null;
  }

  @Override
  public AttemptData sendVerificationCode(
      final MessageTransport messageTransport,
      final Phonenumber.PhoneNumber phoneNumber,
      final List<Locale.LanguageRange> languageRanges,
      final ClientType clientType) throws UnsupportedMessageTransportException {
    final String code = String.format("%06d", new Random().nextInt(1_000_000));
    return new AttemptData(Optional.empty(), code.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public boolean checkVerificationCode(final String verificationCode, final byte[] senderData) {
    return Strings.equalsConstantTime(verificationCode, new String(senderData, StandardCharsets.UTF_8));
  }
}
