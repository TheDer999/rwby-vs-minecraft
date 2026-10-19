using System.Text;
using AssetsTools.NET;
using AssetsTools.NET.Extra;

namespace GrimmConvert;

/// <summary>
/// Thin wrapper over AssetsManager for Unity 5.x serialized files that have no embedded type trees.
/// Read-only: it never writes to the game folder.
/// </summary>
sealed class UnityAssets
{
    public const int GameObject = 1, MeshRenderer = 23, Texture2D = 28, MeshFilter = 33, Mesh = 43,
        AnimationClip = 74, SkinnedMeshRenderer = 137;

    readonly AssetsManager am = new();
    readonly string dataDir;
    readonly string preferredFile;

    public UnityAssets(string dataDir, string classDataPath, string preferredFile)
    {
        this.dataDir = dataDir;
        this.preferredFile = preferredFile;
        am.LoadClassPackage(classDataPath);
    }

    /// <summary>Serialized files in the data folder, the preferred one first (it holds nearly everything).</summary>
    public IEnumerable<string> FilePaths()
    {
        var all = Directory.GetFiles(dataDir).Where(f =>
        {
            var n = Path.GetFileName(f);
            if (n.EndsWith(".resS") || n.EndsWith(".resource") || n.EndsWith(".dll") || n.EndsWith(".txt")) return false;
            return !n.Contains('.') || n.EndsWith(".assets");
        }).OrderBy(f => f, StringComparer.Ordinal).ToList();
        var first = all.FirstOrDefault(f => Path.GetFileName(f) == preferredFile);
        if (first != null) yield return first;
        foreach (var f in all) if (f != first) yield return f;
    }

    public AssetsFileInstance Open(string path)
    {
        var inst = am.LoadAssetsFile(path, false);
        am.LoadClassDatabaseFromPackage(inst.file.Metadata.UnityVersion);
        return inst;
    }

    public string DataDir => dataDir;

    /// <summary>Lets ScriptableObject fields be read, using the game's own Managed assemblies (read-only).</summary>
    public void EnableScripts(string managedDir) => am.MonoTempGenerator = new MonoCecilTempGenerator(managedDir);

    public AssetTypeValueField Base(AssetsFileInstance inst, AssetFileInfo info) => am.GetBaseField(inst, info);

    public AssetExternal Resolve(AssetsFileInstance from, AssetTypeValueField pptr) => am.GetExtAsset(from, pptr);

    /// <summary>
    /// The m_Name of an object whose first serialized field is m_Name (Mesh, Texture2D, AnimationClip, Material).
    /// Read straight from the bytes: much faster than building the whole object.
    /// </summary>
    public static string NameOf(AssetsFileInstance inst, AssetFileInfo info)
    {
        var r = inst.file.Reader;
        r.Position = info.GetAbsoluteByteOffset(inst.file);
        var len = r.ReadInt32();
        return len >= 0 && len < 300 ? Encoding.UTF8.GetString(r.ReadBytes(len)) : "?";
    }

    /// <summary>Reads a file that sits next to the serialized files (Unity stores big texture data there).</summary>
    public byte[] ReadStream(string relativePath, long offset, long size)
    {
        var p = Path.Combine(dataDir, Path.GetFileName(relativePath));
        using var fs = File.OpenRead(p);
        fs.Seek(offset, SeekOrigin.Begin);
        var data = new byte[size];
        fs.ReadExactly(data, 0, (int)size);
        return data;
    }
}

/// <summary>A failure with a stable exit code and a message fit to show a player.</summary>
sealed class ConvertException : Exception
{
    public int Code { get; }

    public ConvertException(int code, string message) : base(message) { Code = code; }
}

static class ExitCodes
{
    public const int Ok = 0;
    public const int Unexpected = 1;
    public const int GameNotFound = 2;
    public const int AssetMissing = 3;
    public const int UnsupportedTexture = 4;
    public const int Io = 5;
    public const int Usage = 64;
}
