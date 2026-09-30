// Generates the three GenericServerSecretParams a Signal-Server deployment needs
// (callingZkConfigPreV101, callingZkConfig, chatZkConfig) and prints both halves.
// Signal-Server has no CLI command for these; run this against the shaded server jar:
//
//   java -cp services/server/service/target/TextSecureServer-*.jar tools/server/GenGenericParams.java
//
// Put each "secret" into the secrets bundle and each "public" into the brand profile
// (generic_server_public_params for calling, backup_server_public_params for chat).
import java.util.Base64;
import org.signal.libsignal.zkgroup.GenericServerSecretParams;

public class GenGenericParams {
  public static void main(String[] args) {
    for (String name : new String[] {"callingZkConfigPreV101", "callingZkConfig", "chatZkConfig"}) {
      GenericServerSecretParams secret = GenericServerSecretParams.generate();
      System.out.println(name + ".serverSecret (secrets bundle): " + Base64.getEncoder().encodeToString(secret.serialize()));
      System.out.println(name + ".serverPublic (clients):        " + Base64.getEncoder().encodeToString(secret.getPublicParams().serialize()));
      System.out.println();
    }
  }
}
