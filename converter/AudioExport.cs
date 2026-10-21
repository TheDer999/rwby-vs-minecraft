using System.Diagnostics;
using AssetsTools.NET;
using AssetsTools.NET.Extra;

namespace GrimmConvert;

/// <summary>
/// Converts a character's sound effects and voice lines from the player's own Grimm Eclipse install into WAV files for the
/// mod. The game keeps them as FMOD banks (one tiny FSB5 file per AudioClip); each is cut out of the game's resource file
/// and decoded by vgmstream-cli (ISC licence), which ships next to the converter. Nothing is written to the game folder.
/// A failure here never fails the conversion: the mod simply has no Grimm Eclipse sounds and uses Minecraft's own.
/// </summary>
static class AudioExport
{
    public sealed record Result(int Wanted, int Written);

    /// <summary>Which AudioClips belong to the character: the names Grimm Eclipse gives her sounds and voice lines.</summary>
    public static bool IsCharacterSound(string name) =>
        name.StartsWith("ruby_", StringComparison.Ordinal) || name.StartsWith("Ruby - ", StringComparison.Ordinal)
        || name.StartsWith("weap_crescentrose_", StringComparison.Ordinal);

    public static string FileName(string clipName) => "sound_" + clipName.Replace(' ', '_').Replace("-", "") .Replace("__", "_") + ".wav";

    public static Result Export(UnityAssets ua, string outDir, Action<string> log)
    {
        var exe = Path.Combine(AppContext.BaseDirectory, "vgmstream", "vgmstream-cli.exe");
        if (!File.Exists(exe))
        {
            log("Sounds skipped: the decoder (vgmstream-cli.exe) is not next to the converter.");
            return new Result(0, 0);
        }
        var temp = Path.Combine(outDir, "_fsb");
        Directory.CreateDirectory(temp);
        int wanted = 0, written = 0;
        try
        {
            foreach (var path in ua.FilePaths())
            {
                var inst = ua.Open(path);
                foreach (var info in inst.file.AssetInfos.Where(i => i.TypeId == 83))
                {
                    string name;
                    try { name = UnityAssets.NameOf(inst, info); } catch { continue; }
                    if (!IsCharacterSound(name)) continue;
                    wanted++;
                    try
                    {
                        var bf = ua.Base(inst, info);
                        var res = bf["m_Resource"];
                        string src = res["m_Source"].AsString;
                        long off = res["m_Offset"].AsLong, size = res["m_Size"].AsLong;
                        if (size <= 0 || size > 16_000_000) continue;
                        var data = ua.ReadStream(src, off, size);
                        var fsb = Path.Combine(temp, written + ".fsb");
                        File.WriteAllBytes(fsb, data);
                        var wav = Path.Combine(outDir, FileName(name));
                        if (Decode(exe, fsb, wav)) written++;
                    }
                    catch (Exception e) when (e is not ConvertException)
                    {
                        log($"Sound '{name}' could not be converted: {e.Message}");
                    }
                }
            }
        }
        finally
        {
            try { Directory.Delete(temp, true); } catch { /* leftover temp folder is harmless */ }
        }
        log($"Sounds: {written} of {wanted} converted.");
        return new Result(wanted, written);
    }

    static bool Decode(string exe, string fsb, string wav)
    {
        var psi = new ProcessStartInfo(exe)
        {
            UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true,
        };
        psi.ArgumentList.Add("-o");
        psi.ArgumentList.Add(wav);
        psi.ArgumentList.Add(fsb);
        using var p = Process.Start(psi);
        if (p == null) return false;
        p.StandardOutput.ReadToEnd();
        p.StandardError.ReadToEnd();
        if (!p.WaitForExit(20000)) { try { p.Kill(); } catch { } return false; }
        return p.ExitCode == 0 && File.Exists(wav) && new FileInfo(wav).Length > 44;
    }
}
