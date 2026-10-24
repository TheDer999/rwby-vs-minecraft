using System.Text.RegularExpressions;

namespace GrimmConvert;

/// <summary>Developer aid: lists the meshes, textures and animation clips whose name matches a pattern (GrimmConvert --ge dir --list regex).</summary>
static class Explore
{
    public static int List(string dataDir, string pattern)
    {
        var classData = Path.Combine(AppContext.BaseDirectory, "classdata.tpk");
        var ua = new UnityAssets(dataDir, classData, "sharedassets0.assets");
        var re = new Regex(pattern, RegexOptions.IgnoreCase);
        var names = new SortedSet<string>(StringComparer.Ordinal);
        foreach (var path in ua.FilePaths())
        {
            var inst = ua.Open(path);
            foreach (var info in inst.file.AssetInfos)
            {
                string kind = info.TypeId switch
                {
                    UnityAssets.Mesh => "mesh",
                    UnityAssets.Texture2D => "texture",
                    UnityAssets.AnimationClip => "clip",
                    _ => null,
                };
                if (kind == null) continue;
                string name;
                try { name = UnityAssets.NameOf(inst, info); } catch { continue; }
                if (re.IsMatch(name)) names.Add($"{kind}\t{name}\t{Path.GetFileName(path)}");
            }
        }
        foreach (var n in names) Console.WriteLine(n);
        return 0;
    }

    /// <summary>Developer aid: the bones of the skinned mesh with this name, and the clips that animate those bones (GrimmConvert --ge dir --bones mesh).</summary>
    public static int Bones(string dataDir, string meshSpec)
    {
        var parts = meshSpec.Split('|');
        string meshName = parts[0], texHint = parts.Length > 1 ? parts[1] : null;
        var classData = Path.Combine(AppContext.BaseDirectory, "classdata.tpk");
        var ua = new UnityAssets(dataDir, classData, "sharedassets0.assets");
        var bones = new List<string>();
        foreach (var path in ua.FilePaths())
        {
            var inst = ua.Open(path);
            foreach (var info in inst.file.AssetInfos.Where(i => i.TypeId == UnityAssets.SkinnedMeshRenderer))
            {
                var bf = ua.Base(inst, info);
                var me = ua.Resolve(inst, bf["m_Mesh"]);
                if (me.info == null || me.info.TypeId != UnityAssets.Mesh || UnityAssets.NameOf(me.file, me.info) != meshName) continue;
                if (texHint != null && !bf["m_Materials"]["Array"].Children.Any(m =>
                    {
                        var mat = ua.Resolve(inst, m);
                        if (mat.info == null) return false;
                        foreach (var te in ua.Base(mat.file, mat.info)["m_SavedProperties"]["m_TexEnvs"]["Array"].Children)
                        {
                            var tx = ua.Resolve(mat.file, te["second"]["m_Texture"]);
                            if (tx.info != null && tx.info.TypeId == UnityAssets.Texture2D && UnityAssets.NameOf(tx.file, tx.info).Contains(texHint)) return true;
                        }
                        return false;
                    })) continue;
                foreach (var bp in bf["m_Bones"]["Array"].Children)
                {
                    var tr = ua.Resolve(inst, bp);
                    var trbf = ua.Base(tr.file, tr.info);
                    var go = ua.Resolve(tr.file, trbf["m_GameObject"]);
                    bones.Add(ua.Base(go.file, go.info)["m_Name"].AsString);
                }
                break;
            }
            if (bones.Count > 0) break;
        }
        Console.WriteLine($"bones ({bones.Count}): " + string.Join(", ", bones.Take(12)) + (bones.Count > 12 ? ", ..." : ""));
        if (bones.Count == 0) return 3;
        var leaf = new HashSet<string>(bones);
        foreach (var path in ua.FilePaths())
        {
            var inst = ua.Open(path);
            var byRoot = new SortedDictionary<string, List<string>>(StringComparer.Ordinal);
            foreach (var info in inst.file.AssetInfos.Where(i => i.TypeId == UnityAssets.AnimationClip))
            {
                var bf = ua.Base(inst, info);
                int hit = 0; string root = null;
                foreach (var c in bf["m_RotationCurves"]["Array"].Children)
                {
                    var pth = c["path"].AsString.Replace("\\", "/");
                    if (leaf.Contains(pth.Split('/').Last())) { hit++; root ??= pth.Split('/').First(); }
                }
                if (hit >= bones.Count / 2 && root != null)
                {
                    if (!byRoot.TryGetValue(root, out var l)) byRoot[root] = l = new List<string>();
                    l.Add(UnityAssets.NameOf(inst, info));
                }
            }
            foreach (var kv in byRoot) Console.WriteLine($"root '{kv.Key}' in {Path.GetFileName(path)}: {kv.Value.Count} clips: " + string.Join(", ", kv.Value.Distinct().Take(60)));
        }
        return 0;
    }

    /// <summary>Developer aid: every skinned mesh renderer using a mesh of this name, with its materials and their main textures.</summary>
    public static int Smr(string dataDir, string meshName)
    {
        var classData = Path.Combine(AppContext.BaseDirectory, "classdata.tpk");
        var ua = new UnityAssets(dataDir, classData, "sharedassets0.assets");
        foreach (var path in ua.FilePaths())
        {
            var inst = ua.Open(path);
            foreach (var info in inst.file.AssetInfos.Where(i => i.TypeId == UnityAssets.SkinnedMeshRenderer))
            {
                var bf = ua.Base(inst, info);
                var me = ua.Resolve(inst, bf["m_Mesh"]);
                if (me.info == null || me.info.TypeId != UnityAssets.Mesh || UnityAssets.NameOf(me.file, me.info) != meshName) continue;
                var mats = new List<string>();
                foreach (var m in bf["m_Materials"]["Array"].Children)
                {
                    var mat = ua.Resolve(inst, m);
                    if (mat.info == null) { mats.Add("(missing)"); continue; }
                    var mbf = ua.Base(mat.file, mat.info);
                    var texes = new List<string>();
                    foreach (var te in mbf["m_SavedProperties"]["m_TexEnvs"]["Array"].Children)
                    {
                        var tex = ua.Resolve(mat.file, te["second"]["m_Texture"]);
                        if (tex.info != null && tex.info.TypeId == UnityAssets.Texture2D) texes.Add(te["first"]["name"].AsString + "=" + UnityAssets.NameOf(tex.file, tex.info));
                    }
                    mats.Add(mbf["m_Name"].AsString + "{" + string.Join(",", texes) + "}");
                }
                Console.WriteLine($"{Path.GetFileName(path)} #{info.PathId} mesh in {Path.GetFileName(me.file.path)}: " + string.Join(" | ", mats));
            }
        }
        return 0;
    }

    /// <summary>Developer aid: the skinned meshes whose materials use a texture with this name, with their bone counts.</summary>
    public static int SmrByTexture(string dataDir, string texHint)
    {
        var classData = Path.Combine(AppContext.BaseDirectory, "classdata.tpk");
        var ua = new UnityAssets(dataDir, classData, "sharedassets0.assets");
        var seen = new HashSet<string>();
        foreach (var path in ua.FilePaths())
        {
            var inst = ua.Open(path);
            foreach (var info in inst.file.AssetInfos.Where(i => i.TypeId == UnityAssets.SkinnedMeshRenderer))
            {
                var bf = ua.Base(inst, info);
                var me = ua.Resolve(inst, bf["m_Mesh"]);
                if (me.info == null || me.info.TypeId != UnityAssets.Mesh) continue;
                bool hit = false;
                foreach (var m in bf["m_Materials"]["Array"].Children)
                {
                    var mat = ua.Resolve(inst, m);
                    if (mat.info == null) continue;
                    foreach (var te in ua.Base(mat.file, mat.info)["m_SavedProperties"]["m_TexEnvs"]["Array"].Children)
                    {
                        var tx = ua.Resolve(mat.file, te["second"]["m_Texture"]);
                        if (tx.info != null && tx.info.TypeId == UnityAssets.Texture2D && UnityAssets.NameOf(tx.file, tx.info).Contains(texHint, StringComparison.OrdinalIgnoreCase)) hit = true;
                    }
                }
                if (!hit) continue;
                var line = $"{Path.GetFileName(path)} mesh '{UnityAssets.NameOf(me.file, me.info)}' bones {bf["m_Bones"]["Array"].Children.Count} materials {bf["m_Materials"]["Array"].Children.Count}";
                if (seen.Add(line)) Console.WriteLine(line);
            }
        }
        return 0;
    }
}