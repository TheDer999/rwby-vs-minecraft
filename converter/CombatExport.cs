using System.Text;
using System.Text.Json;
using AssetsTools.NET;
using AssetsTools.NET.Extra;

namespace GrimmConvert;

/// <summary>
/// Reads a character's combat numbers from the player's own game: which ability fills each role (light combo, heavy,
/// dodge, special...) and, for every ability, its timings, animation names and the attack volumes and hit values it
/// uses. The abilities are ScriptableObjects, so their fields are read through the game's own Managed assemblies
/// (read-only), found next to the data files.
/// </summary>
static class CombatExport
{
    public sealed record Result(int Abilities, int Roles);

    /// <param name="scriptClass">MonoBehaviour class of the character component (e.g. "Ruby").</param>
    public static Result Export(UnityAssets ua, string scriptClass, string outFile, Action<string> log)
    {
        var managed = Path.Combine(ua.DataDir, "Managed");
        if (!Directory.Exists(managed))
            throw new ConvertException(ExitCodes.AssetMissing, $"This Grimm Eclipse install has no Managed folder next to its data files ('{managed}'), so combat data cannot be read.");
        ua.EnableScripts(managed);

        // The character's component holds m_playerAbilities; the component with the most filled slots is the real one.
        (AssetsFileInstance inst, AssetTypeValueField bf, int filled)? best = null;
        foreach (var path in ua.FilePaths())
        {
            var inst = ua.Open(path);
            foreach (var info in inst.file.AssetInfos.Where(i => i.GetScriptIndex(inst.file) != 0xFFFF))
            {
                try
                {
                    var bf = ua.Base(inst, info);
                    if (ScriptClass(ua, inst, bf) != scriptClass) continue;
                    var pa = bf["m_playerAbilities"];
                    if (pa.IsDummy) continue;
                    int filled = 0;
                    foreach (var c in pa.Children)
                    {
                        if (c.TemplateField.Type == "vector") filled += c["Array"].Children.Count(e => e["m_PathID"].AsLong != 0);
                        else if (!c["m_PathID"].IsDummy && c["m_PathID"].AsLong != 0) filled++;
                    }
                    if (best == null || filled > best.Value.filled) best = (inst, bf, filled);
                }
                catch (Exception e) when (e is not ConvertException) { /* not a readable component: skip it */ }
            }
        }
        if (best == null || best.Value.filled == 0)
            throw new ConvertException(ExitCodes.AssetMissing, $"Could not find {scriptClass}'s ability list in this Grimm Eclipse install.");
        var charInst = best.Value.inst;
        var charBf = best.Value.bf;

        // Upgrade-variant specials (Ruby_Ultimate_*) are not in m_playerAbilities; find them by name.
        var specials = new List<(AssetsFileInstance f, AssetFileInfo i, string n)>();
        foreach (var path in ua.FilePaths())
        {
            var inst = ua.Open(path);
            foreach (var info in inst.file.AssetInfos.Where(i => i.GetScriptIndex(inst.file) != 0xFFFF))
            {
                try
                {
                    var bf = ua.Base(inst, info);
                    var nm = bf["m_Name"].AsString;
                    if (!nm.StartsWith(scriptClass + "_Ultimate", StringComparison.Ordinal)) continue;
                    if (ScriptClass(ua, inst, bf) != "AbilityData") continue;
                    specials.Add((inst, info, nm));
                }
                catch (Exception e) when (e is not ConvertException) { }
            }
        }
        specials = specials.OrderBy(s => s.n, StringComparer.Ordinal).ToList();

        using var ms = new MemoryStream();
        using (var w = new Utf8JsonWriter(ms, new JsonWriterOptions { Indented = true }))
        {
            var written = new HashSet<string>();
            var all = new List<(AssetsFileInstance f, AssetFileInfo i)>();
            int roles = 0;

            w.WriteStartObject();
            w.WriteString("character", scriptClass);
            w.WriteStartObject("roles");
            foreach (var c in charBf["m_playerAbilities"].Children)
            {
                if (c.TemplateField.Type == "vector")
                {
                    w.WriteStartArray(c.FieldName);
                    foreach (var e in c["Array"].Children)
                    {
                        var r = Res(ua, charInst, e);
                        if (r == null) { w.WriteNullValue(); continue; }
                        w.WriteStringValue(ua.Base(r.Value.f, r.Value.i)["m_Name"].AsString);
                        all.Add(r.Value);
                    }
                    w.WriteEndArray();
                    roles++;
                }
                else
                {
                    var r = Res(ua, charInst, c);
                    if (r == null) continue;
                    w.WriteString(c.FieldName, ua.Base(r.Value.f, r.Value.i)["m_Name"].AsString);
                    all.Add(r.Value);
                    roles++;
                }
            }
            w.WriteStartArray("SpecialAttack");
            foreach (var s in specials) { w.WriteStringValue(s.n); all.Add((s.f, s.i)); }
            w.WriteEndArray();
            roles++;
            w.WriteEndObject();

            w.WriteStartObject("abilities");
            foreach (var (f, i) in all) WriteAbility(ua, w, written, f, i);
            w.WriteEndObject();
            w.WriteEndObject();
            log($"Combat data: {written.Count} abilities, {roles} roles ({specials.Count} special variants).");
            w.Flush();
            ms.Position = 0;
            File.WriteAllBytes(outFile, ms.ToArray());
            return new Result(written.Count, roles);
        }
    }

    static string ScriptClass(UnityAssets ua, AssetsFileInstance inst, AssetTypeValueField bf)
    {
        var scr = ua.Resolve(inst, bf["m_Script"]);
        return scr.info == null ? null : ua.Base(scr.file, scr.info)["m_ClassName"].AsString;
    }

    static (AssetsFileInstance f, AssetFileInfo i)? Res(UnityAssets ua, AssetsFileInstance from, AssetTypeValueField pptr)
    {
        if (pptr == null || pptr.IsDummy || pptr["m_PathID"].AsLong == 0) return null;
        var e = ua.Resolve(from, pptr);
        return e.info == null ? null : (e.file, e.info);
    }

    static string ClipName(UnityAssets ua, AssetsFileInstance from, AssetTypeValueField pptr)
    {
        var r = Res(ua, from, pptr);
        return r == null ? null : UnityAssets.NameOf(r.Value.f, r.Value.i);
    }

    static void WriteAbility(UnityAssets ua, Utf8JsonWriter w, HashSet<string> written, AssetsFileInstance fi, AssetFileInfo inf)
    {
        var b = ua.Base(fi, inf);
        var nm = b["m_Name"].AsString;
        if (!written.Add(nm)) return;
        w.WriteStartObject(nm);
        w.WriteString("coroutine", b["CoroutineName"].AsString);
        w.WriteNumber("cooldownSeconds", b["CooldownSeconds"].AsFloat);
        w.WriteNumber("waitForComboInputSeconds", b["WaitForComboInputSeconds"].AsFloat);
        w.WriteNumber("comboType", b["ComboType"].AsInt);
        w.WriteBoolean("increaseComboCounter", b["IncreaseComboCounter"].AsBool);
        w.WriteBoolean("resetComboCounter", b["ResetComboCounter"].AsBool);
        w.WriteNumber("specialCost", b["SpecialCost"].AsInt);
        w.WriteBoolean("uninterruptible", b["IsUninterruptible"].AsBool);
        w.WriteBoolean("isTeamAttack", b["IsTeamAttack"].AsBool);
        w.WriteNumber("chargeType", b["ChargeType"].AsInt);
        w.WriteNumber("fullChargeTimeSeconds", b["FullChargeTimeSeconds"].AsFloat);
        w.WriteNumber("maxChargeTimeSeconds", b["MaxChargeTimeSeconds"].AsFloat);
        w.WriteNumber("targetMinRange", b["TargetingFilters"]["MinRange"].AsFloat);
        w.WriteNumber("targetMaxRange", b["TargetingFilters"]["MaxRange"].AsFloat);
        w.WriteNumber("primaryProjectileCount", b["PrimaryProjectileCount"].AsInt);
        w.WriteBoolean("hasPrimaryProjectile", Res(ua, fi, b["PrimaryProjectile"]) != null);
        w.WriteStartObject("animations");
        foreach (var an in new[] { "TelegraphAnimation", "WindupAnimation", "PrimaryAnimation", "SecondaryAnimation", "LoopStartAnimation", "LoopAnimation", "LoopEndAnimation" })
        {
            var cn = ClipName(ua, fi, b[an]);
            if (cn != null) w.WriteString(an, cn);
        }
        w.WriteEndObject();
        w.WriteStartObject("attacks");
        WriteAttack(ua, w, "primary", fi, b["PrimaryAttack"]);
        WriteAttack(ua, w, "secondary", fi, b["SecondaryAttack"]);
        WriteAttack(ua, w, "tertiary", fi, b["TertiaryAttack"]);
        w.WriteEndObject();
        w.WriteEndObject();
    }

    static void WriteAttack(UnityAssets ua, Utf8JsonWriter w, string key, AssetsFileInstance from, AssetTypeValueField pptr)
    {
        var r = Res(ua, from, pptr);
        if (r == null) return;
        var a = ua.Base(r.Value.f, r.Value.i);
        w.WriteStartObject(key);
        w.WriteString("name", a["m_Name"].AsString);
        var col = Res(ua, r.Value.f, a["Collider"]);
        if (col != null)
        {
            var cb = ua.Base(col.Value.f, col.Value.i);
            w.WriteNumber("radius", cb["m_Radius"].AsFloat);
            float cx = cb["m_Center"]["x"].AsFloat, cy = cb["m_Center"]["y"].AsFloat, cz = cb["m_Center"]["z"].AsFloat;
            // The hit sphere sits at the collider object's world position relative to the character (the game uses transform.position).
            var go = Res(ua, col.Value.f, cb["m_GameObject"]);
            float px = 0, py = 0, pz = 0; int depth = 0; string parents = "";
            if (go != null)
            {
                var gbf = ua.Base(go.Value.f, go.Value.i);
                AssetTypeValueField tr = null; AssetsFileInstance trFile = go.Value.f;
                foreach (var comp in gbf["m_Component"]["Array"].Children)
                {
                    var ce = Res(ua, go.Value.f, comp["second"]);
                    if (ce != null && ce.Value.i.TypeId == 4) { tr = ua.Base(ce.Value.f, ce.Value.i); trFile = ce.Value.f; break; }
                }
                while (tr != null && depth < 8)
                {
                    px += tr["m_LocalPosition"]["x"].AsFloat; py += tr["m_LocalPosition"]["y"].AsFloat; pz += tr["m_LocalPosition"]["z"].AsFloat;
                    var father = Res(ua, trFile, tr["m_Father"]);
                    depth++;
                    if (father == null) break;
                    parents += "^";
                    tr = ua.Base(father.Value.f, father.Value.i); trFile = father.Value.f;
                }
            }
            w.WriteStartArray("colliderCenter"); w.WriteNumberValue(cx); w.WriteNumberValue(cy); w.WriteNumberValue(cz); w.WriteEndArray();
            w.WriteStartArray("offset"); w.WriteNumberValue(px); w.WriteNumberValue(py); w.WriteNumberValue(pz); w.WriteEndArray();
            w.WriteString("offsetParents", parents);
        }
        WriteHit(ua, w, "hit", r.Value.f, a["HitInfo"]);
        WriteHit(ua, w, "criticalHit", r.Value.f, a["CriticalHitInfo"]);
        w.WriteNumber("teamAttackProbability", a["TeamAttackProbability"].AsFloat);
        w.WriteBoolean("automaticTeamAttack", a["AutomaticTeamAttack"].AsBool);
        w.WriteEndObject();
    }

    static void WriteHit(UnityAssets ua, Utf8JsonWriter w, string key, AssetsFileInstance from, AssetTypeValueField pptr)
    {
        var r = Res(ua, from, pptr);
        if (r == null) return;
        var h = ua.Base(r.Value.f, r.Value.i);
        w.WriteStartObject(key);
        w.WriteString("name", h["m_Name"].AsString);
        foreach (var fld in new[] { "baseDamage", "knockBack", "knockUp", "slowMultiplier", "hitTime", "StatusEffectTime" }) w.WriteNumber(fld, h[fld].AsFloat);
        foreach (var fld in new[] { "FullShieldHealthDamage", "PartialShieldHealthDamage", "NoShieldHealthDamage", "GuardDamage", "hitchainAmount", "progressTowardsSpecial", "StatusEffect", "ChainCount" }) w.WriteNumber(fld, h[fld].AsInt);
        foreach (var fld in new[] { "isSmash", "onlyHitGround", "countTowardsHitchain", "IgnoreDamageMultipliers" }) w.WriteBoolean(fld, h[fld].AsBool);
        w.WriteEndObject();
    }
}
