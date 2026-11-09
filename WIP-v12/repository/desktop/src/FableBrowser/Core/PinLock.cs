using System.Security.Cryptography;
using System.Text;

namespace FableBrowser.Core;

public static class PinLock
{
    const int Iterations = 120000;

    public static bool HasPin(Settings settings) => settings.IncognitoPinHash.Length > 0 && settings.IncognitoPinSalt.Length > 0;

    public static void SetPin(Settings settings, string pin)
    {
        var salt = RandomNumberGenerator.GetBytes(16);
        settings.IncognitoPinSalt = Convert.ToBase64String(salt);
        settings.IncognitoPinHash = Convert.ToBase64String(Derive(pin, salt));
    }

    public static void ClearPin(Settings settings)
    {
        settings.IncognitoPinSalt = "";
        settings.IncognitoPinHash = "";
    }

    public static bool Verify(Settings settings, string pin)
    {
        if (!HasPin(settings)) return true;
        try
        {
            var salt = Convert.FromBase64String(settings.IncognitoPinSalt);
            var expected = Convert.FromBase64String(settings.IncognitoPinHash);
            return CryptographicOperations.FixedTimeEquals(Derive(pin, salt), expected);
        }
        catch (FormatException)
        {
            return false;
        }
    }

    static byte[] Derive(string pin, byte[] salt) => Rfc2898DeriveBytes.Pbkdf2(Encoding.UTF8.GetBytes(pin), salt, Iterations, HashAlgorithmName.SHA256, 32);
}
