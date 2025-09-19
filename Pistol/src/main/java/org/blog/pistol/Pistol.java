package org.blog.pistol;

import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Pistol extends JavaPlugin implements Listener, TabExecutor {

    // ===== 설정 =====
    private static final String PISTOL_NAME = ChatColor.GOLD + "Pistol";
    private static final Material PISTOL_MATERIAL = Material.WOODEN_SWORD; // 나무검 사용
    private static final Integer PISTOL_CMD = null; // 리소스팩 CMD 쓰면 숫자 넣기

    private static final double BASE_DAMAGE  = 2.1;   // 고정 피해
    private static final double CLOSE_DAMAGE = 1.05;  // 2칸 이내 근접 사격
    private static final double CLOSE_RANGE  = 2.0;   // 블록 단위
    private static final double SHIELD_REDUCE = 0.75; // 방패 정면 25% 감소

    private static final int RELOAD_TICKS        = 20 * 6; // 장전 6초
    private static final int SLOW_SHOOTER_TICKS  = 20 * 3; // 사수 구속 II 3초
    private static final int SLOW_RELOAD_TICKS   = 20 * 3; // 장전 시작 후 3초 구속 II
    private static final int UNLUCK_TICKS        = 20 * 3; // 피격자 불운 3초

    private static final double MAX_RANGE = 120.0; // 히트스캔 사거리

    // 사운드
    private static final Sound FIRE_SOUND   = Sound.ENTITY_GENERIC_EXPLODE;
    private static final float FIRE_VOL = 0.7f, FIRE_PITCH = 1.6f;
    private static final Sound RELOAD_START = Sound.ITEM_CROSSBOW_LOADING_START;
    private static final Sound RELOAD_END   = Sound.ITEM_CROSSBOW_LOADING_END;

    // ===== 상태 =====
    private final Map<UUID, Integer> reloadTaskId = new ConcurrentHashMap<>();
    private final Set<UUID> inReload = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastTrigger = new ConcurrentHashMap<>();
    private static final long TRIGGER_DEBOUNCE_MS = 120L; // 좌클릭 중복 방지

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("pistol")).setExecutor(this);
        getLogger().info("PistolPlugin enabled (Paper 1.20.6) — wooden sword left-click fire.");
    }

    @Override
    public void onDisable() {
        for (Integer id : reloadTaskId.values()) getServer().getScheduler().cancelTask(id);
        reloadTaskId.clear();
        inReload.clear();
        lastTrigger.clear();
    }

    // ===== /pistol give =====
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Player only.");
            return true;
        }
        if (args.length == 0 || !args[0].equalsIgnoreCase("give")) {
            p.sendMessage(ChatColor.YELLOW + "사용법: /pistol give");
            return true;
        }
        ItemStack pistol = makePistolItem();
        p.getInventory().addItem(pistol);
        p.sendMessage(ChatColor.GREEN + "피스톨을 지급했습니다.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) return Arrays.asList("give");
        return Collections.emptyList();
    }

    // ===== 아이템 =====
    private ItemStack makePistolItem() {
        ItemStack stack = new ItemStack(PISTOL_MATERIAL);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(PISTOL_NAME);
        meta.setLore(Arrays.asList(
                ChatColor.GRAY + "좌클릭: 발사",
                ChatColor.DARK_GRAY + "어디 맞아도 2.1, 근접(≤2칸) 1.05",
                ChatColor.DARK_GRAY + "방패 정면 25% 감소",
                ChatColor.DARK_GRAY + "발사 후 장전 6초(처음 3초 구속 II)"
        ));
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        if (PISTOL_CMD != null) meta.setCustomModelData(PISTOL_CMD);
        stack.setItemMeta(meta);

        if (stack.getItemMeta() instanceof Damageable dm) {
            dm.setDamage(0);
            stack.setItemMeta((ItemMeta) dm);
        }
        return stack;
    }

    private boolean isPistol(ItemStack item) {
        if (item == null || item.getType() != PISTOL_MATERIAL) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return false;
        if (!PISTOL_NAME.equals(meta.getDisplayName())) return false;
        if (PISTOL_CMD != null) {
            if (!meta.hasCustomModelData() || meta.getCustomModelData() != PISTOL_CMD) return false;
        }
        return true;
    }
    private boolean holdingPistol(Player p) { return isPistol(p.getInventory().getItemInMainHand()); }

    // ===== 좌클릭 처리 (에어/블록) =====
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;

        switch (e.getAction()) {
            case LEFT_CLICK_AIR, LEFT_CLICK_BLOCK -> {
                Player p = e.getPlayer();
                if (!holdingPistol(p)) return;
                if (!tryTrigger(p)) return;

                if (inReload.contains(p.getUniqueId())) {
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.7f);
                    p.sendActionBar(ChatColor.RED + "장전 중…");
                    return;
                }
                fireThenReload(p);
                e.setCancelled(true);
            }
            default -> {}
        }
    }

    // ===== 좌클릭 처리 (엔티티 스윙 애니메이션) =====
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        Player p = e.getPlayer();
        if (!holdingPistol(p)) return;
        if (!tryTrigger(p)) return;

        if (inReload.contains(p.getUniqueId())) {
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.7f);
            p.sendActionBar(ChatColor.RED + "장전 중…");
            return;
        }
        fireThenReload(p);
        // 근접 데미지는 아래 onMelee에서 제어
    }

    // ===== 근접 기본공격 무효화(총알 데미지는 유지) =====
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onMelee(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (!holdingPistol(p)) return;

        long now = System.currentTimeMillis();
        Long last = lastTrigger.get(p.getUniqueId());
        if (last != null && (now - last) <= 200L) {
            return; // 방금 총 발사 → 총알 흐름 허용
        }
        e.setCancelled(true); // 순수 근접 휘두름은 무효
    }

    // ===== 공통 흐름 =====
    private void fireThenReload(Player p) {
        firePistol(p);
        // 장전 시작(6초, 처음 3초 구속 II)
        startReload(p);
    }

    private boolean tryTrigger(Player p) {
        long now = System.currentTimeMillis();
        Long last = lastTrigger.get(p.getUniqueId());
        if (last != null && (now - last) < TRIGGER_DEBOUNCE_MS) return false;
        lastTrigger.put(p.getUniqueId(), now);
        return true;
    }

    // ===== 발사(히트스캔) =====
    private void firePistol(Player p) {
        World w = p.getWorld();
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();

        // 총구 이펙트
        w.playSound(eye, FIRE_SOUND, FIRE_VOL, FIRE_PITCH);
        w.spawnParticle(Particle.CRIT, eye.clone().add(dir.multiply(0.3)), 6, 0.02, 0.02, 0.02, 0.0);

        // 레이트레이스 — Paper 1.20.6: raySize 인자 없는 오버로드 사용
        RayTraceResult blockRt = w.rayTraceBlocks(eye, dir, MAX_RANGE, FluidCollisionMode.NEVER, true);
        RayTraceResult entRt = w.rayTraceEntities(eye, dir, MAX_RANGE,
                (Entity e) -> (e instanceof LivingEntity) && !e.getUniqueId().equals(p.getUniqueId()));

        double blockDist = blockRt != null && blockRt.getHitPosition() != null
                ? blockRt.getHitPosition().distance(eye.toVector()) : Double.POSITIVE_INFINITY;
        double entDist = entRt != null && entRt.getHitPosition() != null
                ? entRt.getHitPosition().distance(eye.toVector()) : Double.POSITIVE_INFINITY;

        // 궤적 가시화
        double pathLen = Math.min(blockDist, entDist);
        if (Double.isInfinite(pathLen)) pathLen = MAX_RANGE;
        drawTrace(w, eye, dir, pathLen);

        // 엔티티가 더 가깝다면 적중
        if (entRt != null && entDist <= blockDist) {
            Entity hit = entRt.getHitEntity();
            if (hit instanceof LivingEntity le) {
                double distance = eye.toVector().distance(entRt.getHitPosition());
                double dmg = (distance <= CLOSE_RANGE) ? CLOSE_DAMAGE : BASE_DAMAGE;

                // 방패 정면 25% 감소
                if (hit instanceof Player victim) {
                    if (victim.isBlocking() && isFrontBlock(victim, p)) {
                        dmg *= SHIELD_REDUCE;
                        victim.getWorld().playSound(victim.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1f, 1.2f);
                    }
                }

                // 피격자: 불운 3초
                le.addPotionEffect(new PotionEffect(PotionEffectType.UNLUCK, UNLUCK_TICKS, 0, false, false, true));
                // 피해 적용(공격자 지정)
                le.damage(dmg, p);

                // 히트 이펙트
                Location hitLoc = entRt.getHitPosition().toLocation(w);
                w.spawnParticle(Particle.ENCHANTED_HIT, hitLoc, 12, 0.1, 0.1, 0.1, 0.02);
                w.playSound(hitLoc, Sound.ENTITY_ARROW_HIT_PLAYER, 0.9f, 1.6f);
            }
        } else if (blockRt != null) {
            // 블록 히트
            Location hitLoc = blockRt.getHitPosition().toLocation(w);
            w.spawnParticle(Particle.SMOKE, hitLoc, 10, 0.05, 0.05, 0.05, 0.0);
            w.playSound(hitLoc, Sound.BLOCK_STONE_HIT, 0.8f, 1.4f);
        }
    }

    // 방패 정면 판정(대략 60° 이내)
    private boolean isFrontBlock(Player defender, Player shooter) {
        Vector toShooter = shooter.getEyeLocation().toVector()
                .subtract(defender.getEyeLocation().toVector())
                .normalize();
        Vector look = defender.getEyeLocation().getDirection().normalize();
        double dot = look.dot(toShooter); // cos(theta)
        return dot > 0.5; // 60°
    }

    // 궤적 파티클 (1.20.6: DUST 사용)
    private void drawTrace(World w, Location start, Vector dir, double length) {
        int steps = (int) (length / 0.8);
        Vector step = dir.clone().multiply(0.8);
        Location cur = start.clone();
        for (int i = 0; i < steps; i++) {
            cur.add(step);
            w.spawnParticle(
                    Particle.DUST,
                    cur,
                    1, 0, 0, 0, 0,
                    new Particle.DustOptions(Color.fromRGB(220, 220, 255), 1.2f)
            );
        }
    }

    // ===== 장전 =====
    private void startReload(Player p) {
        UUID id = p.getUniqueId();
        cancelReload(id);

        inReload.add(id);
        p.getWorld().playSound(p.getLocation(), RELOAD_START, 0.9f, 1.0f);
        // 장전 시작 후 3초 구속 II
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, SLOW_RELOAD_TICKS, 1, false, false, true));
        p.sendActionBar(ChatColor.AQUA + "장전 중… (" + (RELOAD_TICKS / 20) + "s)");

        int task = getServer().getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
            int ticks = 0;
            @Override public void run() {
                // 손에 들고 있어야 유지 (이동은 허용)
                if (!p.isOnline() || !holdingPistol(p)) {
                    cancelReload(id);
                    return;
                }
                if (ticks % 20 == 0) {
                    int left = Math.max(0, (RELOAD_TICKS - ticks) / 20);
                    p.sendActionBar(ChatColor.AQUA + "장전 중… " + left + "s");
                }
                ticks += 2;
                if (ticks >= RELOAD_TICKS) {
                    cancelReload(id);
                    p.getWorld().playSound(p.getLocation(), RELOAD_END, 1f, 1.2f);
                    p.sendActionBar(ChatColor.GREEN + "장전 완료!");
                }
            }
        }, 0L, 2L);

        reloadTaskId.put(id, task);
    }

    private void cancelReload(UUID id) {
        Integer task = reloadTaskId.remove(id);
        if (task != null) getServer().getScheduler().cancelTask(task);
        inReload.remove(id);
    }

    // ===== 장전 취소 트리거 =====
    @EventHandler(ignoreCancelled = true)
    public void onHeldChange(PlayerItemHeldEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        if (inReload.contains(id)) cancelReload(id);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        if (inReload.contains(id)) cancelReload(id);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        if (inReload.contains(id)) cancelReload(id);
    }

    @EventHandler(ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        if (inReload.contains(id)) cancelReload(id);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p) {
            UUID id = p.getUniqueId();
            if (inReload.contains(id)) cancelReload(id);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent e) {
        UUID id = e.getEntity().getUniqueId();
        if (inReload.contains(id)) cancelReload(id);
    }
}
