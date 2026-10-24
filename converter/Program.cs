using System.Security.Cryptography;
using System.Text.Json;

namespace GrimmConvert;

/// <summary>
/// GrimmConvert: reads the player's own copy of RWBY: Grimm Eclipse (read-only) and writes the data the Minecraft mod needs
/// into a private folder. No game asset is shipped; everything is converted on the player's PC.
///
///   GrimmConvert --ge &lt;Grimm Eclipse folder&gt; --out &lt;output folder&gt; [--force] [--check] [--character ruby]
///
/// stdout lines: "PROGRESS n/total text", "INFO text", "DONE text", "UPTODATE", "ERROR code text".
/// Exit codes: 0 ok, 1 unexpected, 2 game not found, 3 asset missing or unknown build, 4 unsupported texture, 5 io, 10 (--check) not ready, 64 usage.
/// </summary>
static class Program
{
    const string ConverterVersion = "0.5.0";
    const string DataFolderPattern = "*_Data";
    const int ExitNotReady = 10;

    // The build this converter was developed against (Steam app 418340, depot build 5951204).
    const long KnownSize = 444620024;
    const string KnownSha256 = "27455B7D39351FD9A006B5E2DCEA0847CBC1D51F0EB46E342EF1E14C72EB288A";
    const string Fingerprinted = "sharedassets0.assets";

    static readonly Dictionary<string, CharacterSpec> Characters = new()
    {
        ["ruby"] = new CharacterSpec("ruby", "RubyBody_GEO", "Ruby", new[] { "crescentRose_mesh", "crescentRose_gun_mesh" }, "Ruby"),
        // The Grimm the mod spawns: model, skeleton and animations only (their behaviour is the mod's own).
        ["beowolf"] = new CharacterSpec("beowolf", "Beowolf_Mesh", "Bip01", Array.Empty<string>(), "Beowolf"),
        ["creep"] = new CharacterSpec("creep", "Creep Mesh", "Creep Root", Array.Empty<string>(), "Creep", "Creep Body"),
        ["ursa"] = new CharacterSpec("ursa", "Ursa", "Ursa Pelvis", Array.Empty<string>(), "Ursa", "Ursa Body", "Creep Armor"),
        ["boar"] = new CharacterSpec("boar", "Boar_Mesh", "Boar_Pelvis", Array.Empty<string>(), "Boar", "Boar Body D"),
        ["deathstalker"] = new CharacterSpec("deathstalker", "Death_Mesh", "Death_Root", Array.Empty<string>(), "Death", "Deathstalker Body"),
    };

    static int Main(string[] args)
    {
        try { return Run(args); }
        catch (ConvertException e)
        {
            Console.WriteLine($"ERROR {e.Code} {e.Message}");
            return e.Code;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            Console.WriteLine($"ERROR {ExitCodes.Io} Could not read or write files: {e.Message}");
            return ExitCodes.Io;
        }
        catch (Exception e)
        {
            Console.WriteLine($"ERROR {ExitCodes.Unexpected} Unexpected failure: {e.Message}");
            Console.Error.WriteLine(e);
            return ExitCodes.Unexpected;
        }
    }

    static int Run(string[] args)
    {
        string ge = null, outDir = null, character = "ruby", list = null, bonesOf = null, smrOf = null, smrTex = null;
        bool force = false, check = false;
        for (int i = 0; i < args.Length; i++)
        {
            switch (args[i])
            {
                case "--list": list = Next(args, ref i); break;
                case "--bones": bonesOf = Next(args, ref i); break;
                case "--smr": smrOf = Next(args, ref i); break;
                case "--smrtex": smrTex = Next(args, ref i); break;
                case "--ge": ge = Next(args, ref i); break;
                case "--out": outDir = Next(args, ref i); break;
                case "--character": character = Next(args, ref i).ToLowerInvariant(); break;
                case "--force": force = true; break;
                case "--check": check = true; break;
                default: throw new ConvertException(ExitCodes.Usage, $"Unknown argument '{args[i]}'.");
            }
        }
        if (list != null && ge != null) return Explore.List(FindDataDir(ge), list);
        if (bonesOf != null && ge != null) return Explore.Bones(FindDataDir(ge), bonesOf);
        if (smrOf != null && ge != null) return Explore.Smr(FindDataDir(ge), smrOf);
        if (smrTex != null && ge != null) return Explore.SmrByTexture(FindDataDir(ge), smrTex);
        if (ge == null || outDir == null)
            throw new ConvertException(ExitCodes.Usage, "Usage: GrimmConvert --ge <Grimm Eclipse folder> --out <output folder> [--force] [--check] [--character ruby]");
        if (!Characters.TryGetValue(character, out var spec))
            throw new ConvertException(ExitCodes.Usage, $"Unknown character '{character}'. Available: {string.Join(", ", Characters.Keys)}.");

        const int total = 9;
        Progress(1, total, "Looking for Grimm Eclipse");
        var dataDir = FindDataDir(ge);
        var fingerprintFile = new FileInfo(Path.Combine(dataDir, Fingerprinted));

        var readyPath = Path.Combine(outDir, "ready.json");
        if (!force && IsReady(readyPath, spec.Id, fingerprintFile))
        {
            Console.WriteLine("UPTODATE");
            return ExitCodes.Ok;
        }
        if (check) return ExitNotReady;

        Progress(2, total, "Checking your copy of Grimm Eclipse");
        var sha = Sha256(fingerprintFile.FullName);
        bool known = fingerprintFile.Length == KnownSize && sha == KnownSha256;
        Info(known ? "Recognised build." : "Unrecognised build: converting by name, results are checked.");

        Directory.CreateDirectory(outDir);
        var tmp = Path.Combine(outDir, ".tmp-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(tmp);
        try
        {
            var classData = Path.Combine(AppContext.BaseDirectory, "classdata.tpk");
            if (!File.Exists(classData))
                throw new ConvertException(ExitCodes.Io, "The converter is incomplete: classdata.tpk is missing next to it.");
            var ua = new UnityAssets(dataDir, classData, Fingerprinted);

            var displayName = char.ToUpperInvariant(spec.Id[0]) + spec.Id.Substring(1);
            Progress(3, total, $"Reading {displayName}'s model and animations");
            var rig = Exporters.ExportRig(ua, spec, Path.Combine(tmp, $"{spec.Id}_rig.json"), Info);

            Progress(4, total, "Converting her outfit texture");
            var body = Exporters.ExportTexture(ua, rig.BodyTexture.inst, rig.BodyTexture.info, Path.Combine(tmp, $"{spec.Id}_texture.png"));

            Progress(5, total, "Reading her weapon");
            var props = Exporters.ExportProps(ua, spec.PropMeshes, Path.Combine(tmp, "crescent_rose.json"), Path.Combine(tmp, "crescent_texture.png"));

            Progress(6, total, $"Reading {displayName}'s moves and damage numbers");
            var combat = CombatExport.Export(ua, spec.ScriptClass, Path.Combine(tmp, $"{spec.Id}_combat.json"), Info);

            Progress(7, total, $"Reading {displayName}'s sounds and voice lines");
            var audio = AudioExport.Export(ua, tmp, Info);

            Progress(8, total, "Reading the Grimm");
            var grimmOut = new List<object>();
            foreach (var gid in new[] { "beowolf", "creep", "ursa", "boar", "deathstalker" })
            {
                try
                {
                    var gr = Exporters.ExportRig(ua, Characters[gid], Path.Combine(tmp, $"{gid}_rig.json"), Info);
                    for (int mi = 0; mi < gr.MaterialTextures.Count; mi++)
                    {
                        var mt = gr.MaterialTextures[mi];
                        if (mt == null) continue;
                        try { Exporters.ExportTexture(ua, mt.Value.inst, mt.Value.info, Path.Combine(tmp, $"{gid}_texture{mi}.png")); }
                        catch (ConvertException e) when (mi > 0) { Info($"{gid} texture {mi} skipped: {e.Message}"); }
                    }
                    grimmOut.Add(new { id = gid, vertices = gr.Vertices, clips = gr.Clips, textures = gr.MaterialTextures.Count });
                }
                catch (Exception e) when (e is ConvertException or IOException or InvalidOperationException or KeyNotFoundException)
                {
                    Info($"{gid} skipped: {e.Message}"); // Ruby still works without the Grimm
                    foreach (var leftover in Directory.GetFiles(tmp, gid + "_*")) File.Delete(leftover);
                }
            }

            Progress(9, total, "Finishing");
            var outputs = new List<object>();
            foreach (var f in Directory.GetFiles(tmp).OrderBy(f => f, StringComparer.Ordinal))
            {
                var name = Path.GetFileName(f);
                File.Move(f, Path.Combine(outDir, name), overwrite: true);
                outputs.Add(new { file = name, bytes = new FileInfo(Path.Combine(outDir, name)).Length });
            }

            var ready = new
            {
                converter = "GrimmConvert",
                converterVersion = ConverterVersion,
                character = spec.Id,
                createdUtc = DateTime.UtcNow.ToString("o"),
                source = new
                {
                    file = Fingerprinted,
                    size = fingerprintFile.Length,
                    modifiedUtc = fingerprintFile.LastWriteTimeUtc.ToString("o"),
                    sha256 = sha,
                    knownBuild = known,
                },
                rig = new { vertices = rig.Vertices, bones = rig.Bones, nodes = rig.Nodes, clips = rig.Clips },
                bodyTexture = new { name = body.Name, width = body.Width, height = body.Height, format = Textures.FormatName(body.Format) },
                combat = new { abilities = combat.Abilities, roles = combat.Roles },
                sounds = new { wanted = audio.Wanted, written = audio.Written },
                grimm = grimmOut,
                weapon = new { parts = props.Parts, texture = new { name = props.Texture.Name, width = props.Texture.Width, height = props.Texture.Height, format = Textures.FormatName(props.Texture.Format) } },
                outputs,
            };
            // ready.json is written last: its presence means every other file is complete.
            var readyTmp = readyPath + ".tmp";
            File.WriteAllText(readyTmp, JsonSerializer.Serialize(ready, new JsonSerializerOptions { WriteIndented = true }));
            File.Move(readyTmp, readyPath, overwrite: true);
        }
        finally
        {
            try { Directory.Delete(tmp, true); } catch { /* leftover temp folder is harmless */ }
        }

        Console.WriteLine($"DONE {outDir}");
        return ExitCodes.Ok;
    }

    static string Next(string[] args, ref int i)
    {
        if (i + 1 >= args.Length) throw new ConvertException(ExitCodes.Usage, $"Missing value after {args[i]}.");
        return args[++i];
    }

    static void Progress(int n, int total, string text) => Console.WriteLine($"PROGRESS {n}/{total} {text}");

    static void Info(string text) => Console.WriteLine($"INFO {text}");

    /// <summary>The Grimm Eclipse data folder (rwby-ge_Data) inside the install folder, or the folder itself.</summary>
    static string FindDataDir(string ge)
    {
        if (!Directory.Exists(ge))
            throw new ConvertException(ExitCodes.GameNotFound, $"Grimm Eclipse was not found at '{ge}'. Install it from Steam first.");
        if (File.Exists(Path.Combine(ge, Fingerprinted))) return ge;
        foreach (var d in Directory.GetDirectories(ge, DataFolderPattern))
            if (File.Exists(Path.Combine(d, Fingerprinted))) return d;
        throw new ConvertException(ExitCodes.GameNotFound, $"'{ge}' does not look like a Grimm Eclipse install (no data folder with {Fingerprinted}).");
    }

    /// <summary>True when ready.json matches this converter, character and the install's size and timestamp.</summary>
    static bool IsReady(string readyPath, string character, FileInfo source)
    {
        if (!File.Exists(readyPath)) return false;
        try
        {
            using var doc = JsonDocument.Parse(File.ReadAllText(readyPath));
            var r = doc.RootElement;
            var s = r.GetProperty("source");
            return r.GetProperty("converterVersion").GetString() == ConverterVersion
                && r.GetProperty("character").GetString() == character
                && s.GetProperty("size").GetInt64() == source.Length
                && s.GetProperty("modifiedUtc").GetString() == source.LastWriteTimeUtc.ToString("o");
        }
        catch (Exception e) when (e is JsonException or KeyNotFoundException or InvalidOperationException)
        {
            return false;
        }
    }

    static string Sha256(string path)
    {
        using var fs = File.OpenRead(path);
        return Convert.ToHexString(SHA256.HashData(fs));
    }
}
