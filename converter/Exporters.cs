using System.Globalization;
using System.Text;
using AssetsTools.NET;
using AssetsTools.NET.Extra;

namespace GrimmConvert;

/// <summary>What to extract for one playable character. Names, not asset ids, so another build still resolves.</summary>
sealed record CharacterSpec(string Id, string BodyMesh, string SkeletonRoot, string[] PropMeshes, string ScriptClass, string TextureContains = null, string TextureExcludes = null, string[] ClipFilter = null, bool UseRoot = false);

/// <summary>Everything read from one Unity Mesh object.</summary>
sealed class MeshData
{
    public int VertexCount;
    public float[] Positions;   // V*3, raw model space
    public float[] Uvs;         // V*2
    public byte[] IndexBytes;   // 16-bit indices
    public (int first, int count)[] Subs;
    public AssetTypeValueField Base;
}

static class Exporters
{
    static readonly CultureInfo Inv = CultureInfo.InvariantCulture;

    static string F(float x) => x.ToString("0.#####", Inv);

    static int FormatSize(int fmt) => fmt switch { 0 => 4, 1 => 2, 2 => 1, 3 => 1, _ => 4 };

    // ---- meshes

    public static MeshData ReadMesh(UnityAssets ua, AssetsFileInstance inst, AssetFileInfo info)
    {
        var mbf = ua.Base(inst, info);
        var vd = mbf["m_VertexData"];
        int vc = vd["m_VertexCount"].AsInt;
        var chans = vd["m_Channels"]["Array"].Children
            .Select(c => (stream: c["stream"].AsInt, off: c["offset"].AsInt, fmt: c["format"].AsInt, dim: c["dimension"].AsInt)).ToArray();
        var data = vd["m_DataSize"].AsByteArray;
        var strides = new int[4];
        foreach (var c in chans) if (c.dim > 0) strides[c.stream] += c.dim * FormatSize(c.fmt);
        var starts = new int[4];
        int pos = 0;
        for (int s = 0; s < 4; s++) { starts[s] = pos; pos += strides[s] * vc; pos = (pos + 15) & ~15; }

        float[] Read(int ci, int maxDim)
        {
            var c = chans[ci];
            var o = new float[vc * maxDim];
            for (int v = 0; v < vc; v++)
            {
                int baseOff = starts[c.stream] + v * strides[c.stream] + c.off;
                for (int d = 0; d < Math.Min(c.dim, maxDim); d++)
                {
                    int p = baseOff + d * FormatSize(c.fmt);
                    o[v * maxDim + d] = c.fmt == 0 ? BitConverter.ToSingle(data, p)
                        : c.fmt == 1 ? (float)BitConverter.ToHalf(data, p) : data[p] / 255f;
                }
            }
            return o;
        }

        // Channel 0 is position, channel 3 is the first UV set.
        return new MeshData
        {
            VertexCount = vc,
            Positions = Read(0, 3),
            Uvs = Read(3, 2),
            IndexBytes = mbf["m_IndexBuffer"]["Array"].AsByteArray,
            Subs = mbf["m_SubMeshes"]["Array"].Children.Select(s => ((int)s["firstByte"].AsUInt / 2, (int)s["indexCount"].AsUInt)).ToArray(),
            Base = mbf,
        };
    }

    static string Indices(byte[] idx) =>
        string.Join(",", Enumerable.Range(0, idx.Length / 2).Select(i => BitConverter.ToUInt16(idx, i * 2)));

    // ---- textures

    public sealed record TextureResult(string Name, int Width, int Height, int Format, string File);

    /// <summary>Decodes a Texture2D to a PNG. Throws a ConvertException for formats that need a Crunch decoder.</summary>
    public static TextureResult ExportTexture(UnityAssets ua, AssetsFileInstance inst, AssetFileInfo info, string outPng)
    {
        var bf = ua.Base(inst, info);
        string name = UnityAssets.NameOf(inst, info);
        int w = bf["m_Width"].AsInt, h = bf["m_Height"].AsInt, fmt = bf["m_TextureFormat"].AsInt;
        if (!Textures.CanDecode(fmt) && !Textures.IsCrunched(fmt))
            throw new ConvertException(ExitCodes.UnsupportedTexture,
                $"Texture '{name}' is stored as {Textures.FormatName(fmt)}, which this converter cannot read yet.");
        byte[] data;
        var sd = bf["m_StreamData"];
        if (!sd.IsDummy && sd["size"].AsUInt > 0)
            data = ua.ReadStream(sd["path"].AsString, sd["offset"].AsUInt, sd["size"].AsUInt);
        else
            data = bf["image data"]["Array"].AsByteArray;
        var rgba = Textures.Decode(data, w, h, fmt)
            ?? throw new ConvertException(ExitCodes.UnsupportedTexture, $"Texture '{name}' ({Textures.FormatName(fmt)}) could not be unpacked.");
        Textures.WritePng(outPng, rgba, w, h);
        return new TextureResult(name, w, h, fmt, Path.GetFileName(outPng));
    }

    /// <summary>The Texture2D bound to _MainTex of a material, or null.</summary>
    static (AssetsFileInstance inst, AssetFileInfo info)? MainTexture(UnityAssets ua, AssetsFileInstance from, AssetTypeValueField materialPptr)
    {
        var mat = ua.Resolve(from, materialPptr);
        if (mat.info == null) return null;
        var mbf = ua.Base(mat.file, mat.info);
        foreach (var te in mbf["m_SavedProperties"]["m_TexEnvs"]["Array"].Children)
        {
            string prop;
            try { prop = te["first"]["name"].AsString; } catch { continue; }
            if (prop != "_MainTex") continue;
            var tex = ua.Resolve(mat.file, te["second"]["m_Texture"]);
            if (tex.info != null && tex.info.TypeId == UnityAssets.Texture2D) return (tex.file, tex.info);
        }
        return null;
    }

    // ---- rig (skinned mesh + skeleton + clips)

    public sealed record RigResult(int Vertices, int Bones, int Nodes, int Clips, string BodyTextureName,
        (AssetsFileInstance inst, AssetFileInfo info) BodyTexture,
        List<(AssetsFileInstance inst, AssetFileInfo info)?> MaterialTextures);

    /// <summary>
    /// The combined local transform of everything above a skeleton root in the model's prefab (an import often leaves a rotation or scale
    /// there), as a row-major matrix, or null when it is the identity. Starts at the transform `from` and climbs; when `stopAt` is given the
    /// climb first goes up to the object of that name, otherwise `from` is the root itself.
    /// </summary>
    static float[] AncestorMatrix(UnityAssets ua, AssetExternal from, Action<string> log, string id, string stopAt = null)
    {
        var chain = new List<AssetExternal>();
        var cur = from;
        bool reached = stopAt == null;
        int guard = 0;
        while (cur.info != null && guard++ < 64)
        {
            var bf = ua.Base(cur.file, cur.info);
            var go = ua.Resolve(cur.file, bf["m_GameObject"]);
            string name = go.info == null ? "?" : ua.Base(go.file, go.info)["m_Name"].AsString;
            if (reached) chain.Add(cur);
            else if (name == stopAt) reached = true;
            var father = ua.Resolve(cur.file, bf["m_Father"]);
            if (father.info == null) break;
            cur = father;
        }
        // chain[0] is the root itself (or the first ancestor above stopAt); we want the transforms strictly above the root
        var above = stopAt == null ? chain.Skip(1).ToList() : chain.ToList();
        float[] world = Identity();
        foreach (var t in above.AsEnumerable().Reverse())
        {
            var bf = ua.Base(t.file, t.info);
            var q = bf["m_LocalRotation"]; var pos = bf["m_LocalPosition"]; var sc = bf["m_LocalScale"];
            var m = Trs(pos["x"].AsFloat, pos["y"].AsFloat, pos["z"].AsFloat, q["x"].AsFloat, q["y"].AsFloat, q["z"].AsFloat, q["w"].AsFloat, sc["x"].AsFloat, sc["y"].AsFloat, sc["z"].AsFloat);
            world = Mul(world, m);
        }
        bool identity = true;
        var id4 = Identity();
        for (int i = 0; i < 16; i++) if (Math.Abs(world[i] - id4[i]) > 1e-4f) identity = false;
        log($"{id}: {above.Count} ancestor transform(s) above the skeleton root, {(identity ? "identity" : "matrix " + string.Join(",", world.Select(v => v.ToString("0.###", Inv))))}");
        return identity ? null : world;
    }

    static float[] Identity() => new float[] { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1 };

    static float[] Mul(float[] a, float[] b)
    {
        var o = new float[16];
        for (int r = 0; r < 4; r++)
            for (int c = 0; c < 4; c++)
                o[r * 4 + c] = a[r * 4] * b[c] + a[r * 4 + 1] * b[4 + c] + a[r * 4 + 2] * b[8 + c] + a[r * 4 + 3] * b[12 + c];
        return o;
    }

    static float[] Trs(float px, float py, float pz, float x, float y, float z, float w, float sx, float sy, float sz) => new float[]
    {
        (1 - 2 * (y * y + z * z)) * sx, 2 * (x * y - z * w) * sy, 2 * (x * z + y * w) * sz, px,
        2 * (x * y + z * w) * sx, (1 - 2 * (x * x + z * z)) * sy, 2 * (y * z - x * w) * sz, py,
        2 * (x * z - y * w) * sx, 2 * (y * z + x * w) * sy, (1 - 2 * (x * x + y * y)) * sz, pz,
        0, 0, 0, 1,
    };

    public static RigResult ExportRig(UnityAssets ua, CharacterSpec spec, string outFile, Action<string> log)
    {
        // 1. The SkinnedMeshRenderer that draws the body mesh.
        AssetsFileInstance smrInst = null;
        AssetFileInfo smrInfo = null;
        AssetExternal meshExt = default;
        foreach (var path in ua.FilePaths())
        {
            var inst = ua.Open(path);
            foreach (var info in inst.file.AssetInfos.Where(i => i.TypeId == UnityAssets.SkinnedMeshRenderer))
            {
                var bf = ua.Base(inst, info);
                var me = ua.Resolve(inst, bf["m_Mesh"]);
                if (me.info == null || me.info.TypeId != UnityAssets.Mesh) continue;
                if (UnityAssets.NameOf(me.file, me.info) != spec.BodyMesh) continue;
                if (spec.TextureContains != null)
                {
                    // One mesh can be shared by variants (a mutant Creep reuses the Creep mesh): take the renderer with the wanted texture.
                    bool wanted = bf["m_Materials"]["Array"].Children.Any(m =>
                    {
                        var tex = MainTexture(ua, inst, m);
                        return tex != null && UnityAssets.NameOf(tex.Value.inst, tex.Value.info).Contains(spec.TextureContains, StringComparison.OrdinalIgnoreCase);
                    });
                    if (!wanted) continue;
                    if (spec.TextureExcludes != null && bf["m_Materials"]["Array"].Children.Any(m =>
                    {
                        var tex = MainTexture(ua, inst, m);
                        return tex != null && UnityAssets.NameOf(tex.Value.inst, tex.Value.info).Contains(spec.TextureExcludes, StringComparison.OrdinalIgnoreCase);
                    })) continue;
                }
                smrInst = inst; smrInfo = info; meshExt = me;
                break;
            }
            if (smrInfo != null) break;
        }
        if (smrInfo == null)
            throw new ConvertException(ExitCodes.AssetMissing, $"Could not find the model '{spec.BodyMesh}' in this copy of Grimm Eclipse.");
        log($"Found {spec.BodyMesh} in {Path.GetFileName(smrInst.path)}");

        // 2. Mesh data, skin weights, bind poses.
        var mesh = ReadMesh(ua, meshExt.file, meshExt.info);
        var skin = mesh.Base["m_Skin"]["Array"].Children.ToArray();
        var bind = mesh.Base["m_BindPose"]["Array"].Children
            .Select(m => Enumerable.Range(0, 16).Select(i => m["e" + (i / 4) + (i % 4)].AsFloat).ToArray()).ToArray();
        if (skin.Length != mesh.VertexCount)
            throw new ConvertException(ExitCodes.AssetMissing, $"'{spec.BodyMesh}' has no skin weights.");

        // 3. Names of the bones the mesh binds to, via the renderer's transforms.
        var sbf = ua.Base(smrInst, smrInfo);
        var boneNames = new List<string>();
        float[] rootMatrix = null;
        foreach (var bp in sbf["m_Bones"]["Array"].Children)
        {
            var tr = ua.Resolve(smrInst, bp);
            var trbf = ua.Base(tr.file, tr.info);
            var go = ua.Resolve(tr.file, trbf["m_GameObject"]);
            var goName = ua.Base(go.file, go.info)["m_Name"].AsString;
            boneNames.Add(goName);
            if (rootMatrix == null && goName == spec.SkeletonRoot) rootMatrix = AncestorMatrix(ua, tr, log, spec.Id);
        }
        if (rootMatrix == null)
        {
            // The skeleton root is not itself bound to the mesh: climb from the first bone up to it.
            var first = ua.Resolve(smrInst, sbf["m_Bones"]["Array"].Children.First());
            rootMatrix = AncestorMatrix(ua, first, log, spec.Id, spec.SkeletonRoot);
        }

        // 4. The body texture is the first material's _MainTex.
        var firstMaterial = sbf["m_Materials"]["Array"].Children.FirstOrDefault();
        var bodyTex = firstMaterial == null ? null : MainTexture(ua, smrInst, firstMaterial);
        if (bodyTex == null)
            throw new ConvertException(ExitCodes.AssetMissing, $"Could not find the texture of '{spec.BodyMesh}'.");

        // 5. Animation clips that animate this character's skeleton root, Idle first.
        var clipRefs = FindClips(ua, spec.SkeletonRoot, boneNames);
        // A skeleton shared with many other moves (the mutant Beowolf's) keeps only the animations the mod plays.
        if (spec.ClipFilter != null) clipRefs = clipRefs.Where(c => spec.ClipFilter.Contains(c.name)).ToList();
        if (clipRefs.Count == 0)
            throw new ConvertException(ExitCodes.AssetMissing, $"Could not find any animations for '{spec.SkeletonRoot}'.");
        log($"Found {clipRefs.Count} animation clips");

        var nodes = new List<string>();
        var clipData = new List<(string name, float stop, Dictionary<string, string> rot, Dictionary<string, string> pos, Dictionary<string, string> scale)>();
        foreach (var (name, inst, info) in clipRefs)
        {
            var cbf = ua.Base(inst, info);
            var rot = new Dictionary<string, string>();
            var posd = new Dictionary<string, string>();
            var scl = new Dictionary<string, string>();
            float stop = 0;

            void Take(string key, int dims, Dictionary<string, string> into)
            {
                foreach (var c in cbf[key]["Array"].Children)
                {
                    var path = c["path"].AsString.Replace("\\", "/");
                    if (!nodes.Contains(path)) nodes.Add(path);
                    var comps = dims == 4 ? new[] { "x", "y", "z", "w" } : new[] { "x", "y", "z" };
                    var ks = c["curve"]["m_Curve"]["Array"].Children.Select(k =>
                    {
                        stop = Math.Max(stop, k["time"].AsFloat);
                        var v = k["value"];
                        return "[" + F(k["time"].AsFloat) + "," + string.Join(",", comps.Select(q => F(v[q].AsFloat))) + "]";
                    });
                    into[path] = "[" + string.Join(",", ks) + "]";
                }
            }

            Take("m_RotationCurves", 4, rot);
            Take("m_PositionCurves", 3, posd);
            Take("m_ScaleCurves", 3, scl);
            clipData.Add((name, stop, rot, posd, scl));
        }
        nodes.Sort(StringComparer.Ordinal); // parents sort before children

        int NodeOfLeaf(string leaf)
        {
            var hits = nodes.Select((p, i) => (p, i)).Where(x => x.p.Split('/').Last() == leaf).ToList();
            return hits.Count >= 1 ? hits[0].i : -1; // a leaf name used twice (a duplicated bone) takes its first node
        }

        var meshBones = boneNames.Select(NodeOfLeaf).ToArray();
        if (meshBones.Any(i => i < 0))
            throw new ConvertException(ExitCodes.AssetMissing, "Some bones of the model are missing from its animations: " + string.Join(", ", boneNames.Where(n => NodeOfLeaf(n) < 0).Take(8)));

        // 6. Write the rig file.
        var sb = new StringBuilder();
        sb.Append("{\"mesh\":\"" + spec.BodyMesh + "\",\"verts\":" + mesh.VertexCount + ",\n");
        sb.Append("\"subs\":[" + string.Join(",", mesh.Subs.Select(s => $"[{s.first},{s.count}]")) + "],\n");
        sb.Append("\"indices\":[" + Indices(mesh.IndexBytes) + "],\n");
        sb.Append("\"pos\":[" + string.Join(",", mesh.Positions.Select(F)) + "],\n");
        sb.Append("\"uv\":[" + string.Join(",", mesh.Uvs.Select(F)) + "],\n");
        sb.Append("\"skinIdx\":[" + string.Join(",", skin.SelectMany(s => Enumerable.Range(0, 4).Select(i => s["boneIndex[" + i + "]"].AsInt))) + "],\n");
        sb.Append("\"skinW\":[" + string.Join(",", skin.SelectMany(s => Enumerable.Range(0, 4).Select(i => F(s["weight[" + i + "]"].AsFloat)))) + "],\n");
        sb.Append("\"bindPoses\":[" + string.Join(",", bind.Select(m => "[" + string.Join(",", m.Select(F)) + "]")) + "],\n");
        sb.Append("\"meshBones\":[" + string.Join(",", meshBones) + "],\n");
        // Only models whose prefab stands the skeleton upright with a rotation above it (the mutant Beowolf, turned 90 degrees) need it.
        if (rootMatrix != null && spec.UseRoot) { rootMatrix[3] = 0; rootMatrix[7] = 0; rootMatrix[11] = 0; sb.Append("\"root\":[" + string.Join(",", rootMatrix.Select(F)) + "],\n"); }
        sb.Append("\"nodes\":[\n" + string.Join(",\n", nodes.Select(p =>
        {
            var parentPath = p.Contains('/') ? p.Substring(0, p.LastIndexOf('/')) : null;
            int parent = parentPath == null ? -1 : nodes.IndexOf(parentPath);
            return "{\"path\":\"" + p.Replace("\"", "'") + "\",\"parent\":" + parent + "}";
        })) + "\n],\n");
        sb.Append("\"clips\":[\n" + string.Join(",\n", clipData.Select(cd =>
        {
            var tracks = new List<string>();
            for (int ni = 0; ni < nodes.Count; ni++)
            {
                var p = nodes[ni];
                if (!cd.rot.ContainsKey(p) && !cd.pos.ContainsKey(p) && !cd.scale.ContainsKey(p)) continue;
                tracks.Add("{\"node\":" + ni + ",\"rot\":" + cd.rot.GetValueOrDefault(p, "[]") + ",\"pos\":" + cd.pos.GetValueOrDefault(p, "[]") + ",\"scale\":" + cd.scale.GetValueOrDefault(p, "[]") + "}");
            }
            return "{\"name\":\"" + cd.name + "\",\"length\":" + F(cd.stop) + ",\"tracks\":[" + string.Join(",", tracks) + "]}";
        })) + "\n]}");
        File.WriteAllText(outFile, sb.ToString());

        var matTex = sbf["m_Materials"]["Array"].Children.Select(m => MainTexture(ua, smrInst, m)).ToList();
        return new RigResult(mesh.VertexCount, bind.Length, nodes.Count, clipData.Count, UnityAssets.NameOf(bodyTex.Value.inst, bodyTex.Value.info), bodyTex.Value, matTex);
    }

    /// <summary>
    /// Every AnimationClip that animates the skeleton root (a rotation curve whose path is exactly the root name),
    /// Idle first, the rest by asset id. A repeated clip name keeps its first copy.
    /// </summary>
    static List<(string name, AssetsFileInstance inst, AssetFileInfo info)> FindClips(UnityAssets ua, string root, List<string> meshBones)
    {
        var found = new List<(string name, AssetsFileInstance inst, AssetFileInfo info)>();
        foreach (var path in ua.FilePaths())
        {
            var inst = ua.Open(path);
            foreach (var info in inst.file.AssetInfos.Where(i => i.TypeId == UnityAssets.AnimationClip))
            {
                var bf = ua.Base(inst, info);
                var curves = bf["m_RotationCurves"]["Array"].Children;
                if (!curves.Any(c => c["path"].AsString == root)) continue;
                // Several characters can share one skeleton root name (Bip01): keep the clips that animate this model's own bones.
                var leafSet = new HashSet<string>(meshBones);
                if (curves.Count(c => leafSet.Contains(c["path"].AsString.Replace("\\", "/").Split('/').Last())) < meshBones.Count / 2) continue;
                found.Add((UnityAssets.NameOf(inst, info), inst, info));
            }
            if (found.Count > 0) break; // clips of one character live in one file
        }
        var seen = new HashSet<string>();
        var ordered = found.OrderBy(c => c.info.PathId).Where(c => seen.Add(c.name)).ToList();
        var idle = ordered.FindIndex(c => c.name == "Idle");
        if (idle > 0)
        {
            var c = ordered[idle];
            ordered.RemoveAt(idle);
            ordered.Insert(0, c);
        }
        return ordered;
    }

    // ---- props (unskinned meshes such as Crescent Rose)

    public sealed record PropResult(int Parts, TextureResult Texture);

    /// <summary>
    /// Finds meshes by name, each used by a MeshFilter with a MeshRenderer, groups them by the texture they use,
    /// and exports the group that has every requested mesh and the largest texture this converter can decode.
    /// </summary>
    public static PropResult ExportProps(UnityAssets ua, string[] meshNames, string jsonOut, string textureOut)
    {
        var records = new List<(string mesh, AssetsFileInstance meshFile, AssetFileInfo meshInfo,
            AssetsFileInstance texFile, AssetFileInfo texInfo, int w, int h, int fmt)>();
        foreach (var path in ua.FilePaths())
        {
            var inst = ua.Open(path);
            foreach (var info in inst.file.AssetInfos.Where(i => i.TypeId == UnityAssets.MeshFilter))
            {
                var bf = ua.Base(inst, info);
                var me = ua.Resolve(inst, bf["m_Mesh"]);
                if (me.info == null || me.info.TypeId != UnityAssets.Mesh) continue;
                var name = UnityAssets.NameOf(me.file, me.info);
                if (!meshNames.Contains(name)) continue;
                var go = ua.Resolve(inst, bf["m_GameObject"]);
                var gbf = ua.Base(go.file, go.info);
                foreach (var comp in gbf["m_Component"]["Array"].Children)
                {
                    var ce = ua.Resolve(go.file, comp["second"]);
                    if (ce.info == null || ce.info.TypeId != UnityAssets.MeshRenderer) continue;
                    var mat = ua.Base(ce.file, ce.info)["m_Materials"]["Array"].Children.FirstOrDefault();
                    var tex = mat == null ? null : MainTexture(ua, ce.file, mat);
                    if (tex == null) continue;
                    var tbf = ua.Base(tex.Value.inst, tex.Value.info);
                    records.Add((name, me.file, me.info, tex.Value.inst, tex.Value.info,
                        tbf["m_Width"].AsInt, tbf["m_Height"].AsInt, tbf["m_TextureFormat"].AsInt));
                }
            }
            if (records.Count > 0) break;
        }

        var groups = records
            .GroupBy(r => (Path.GetFileName(r.texFile.path), r.texInfo.PathId))
            .Where(g => meshNames.All(n => g.Any(r => r.mesh == n)))
            .ToList();
        if (groups.Count == 0)
            throw new ConvertException(ExitCodes.AssetMissing, "Could not find the weapon model in this copy of Grimm Eclipse.");
        var decodable = groups.Where(g => Textures.CanDecode(g.First().fmt)).ToList();
        if (decodable.Count == 0)
        {
            var f = groups.First().First();
            throw new ConvertException(ExitCodes.UnsupportedTexture,
                $"The weapon texture is stored as {Textures.FormatName(f.fmt)}, which this converter cannot read yet.");
        }
        var best = decodable.OrderByDescending(g => g.First().w * g.First().h).ThenBy(g => g.Key.PathId).First();

        var parts = new List<string>();
        foreach (var name in meshNames)
        {
            var rec = best.First(r => r.mesh == name);
            var mesh = ReadMesh(ua, rec.meshFile, rec.meshInfo);
            parts.Add("{\"name\":\"" + name + "\",\"subs\":[" + string.Join(",", mesh.Subs.Select(s => $"[{s.first},{s.count}]")) +
                "],\"indices\":[" + Indices(mesh.IndexBytes) + "],\"pos\":[" + string.Join(",", mesh.Positions.Select(F)) +
                "],\"uv\":[" + string.Join(",", mesh.Uvs.Select(F)) + "]}");
        }
        File.WriteAllText(jsonOut, "{\"parts\":[\n" + string.Join(",\n", parts) + "\n]}");
        var texture = ExportTexture(ua, best.First().texFile, best.First().texInfo, textureOut);
        return new PropResult(parts.Count, texture);
    }
}
