import net.milkbowl.vault.economy.Economy;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.ChatColor;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.Calendar;
import java.util.Date;

public class LotteryPlus extends JavaPlugin {
    private Economy economy = null;
    private Balance balance = new Balance();
    private double ticketPrice;
    private int maxTicketCount;
    private double lotteryPot;
    private double chanceWin;
    private int chancePercent;
    private List<Player> lotteryTicketHolders = new ArrayList<>();
    private boolean isDrawing = false;
    private String lastWinnerName = "";
    private String lastDrawTime;
    private double lastWinnerAmount;
    private String header = "";
    private String footer = "";
    private String prefix = "";
    private Logger logger;
    private int AnnounceInterval;
    private int DrawInterval;
    private int lotteryDrawTaskId;
    private int lotteryAnnounceTaskId;

    private double minDonation;
    private double maxDonation;

    private Map<Player, Long> chanceCommandCooldown = new HashMap<>();
    private int chanceCommandCooldownTaskId;
    private File dataFile;
    private FileConfiguration dataConfig;
    public static FileConfiguration messages;
    private Map<UUID, Long> lastDonationTime = new HashMap<>();
    private int donateCooldown;
    private int minimumChance;
    private int maximumChance;
    private File messagesFile;

    private double startingAmount;
    private int minimumPlayers;
    private boolean showHeaderFooter = true;


    public String formatCurrency(double amount) {
        NumberFormat formatter = NumberFormat.getNumberInstance();
        formatter.setMinimumFractionDigits(2);
        formatter.setMaximumFractionDigits(2);
        return "$" + formatter.format(amount);
    }

    @Override
    public void onEnable() {
        // This code runs when the plugin is enabled
        if (!setupEconomy()) {
            getLogger().severe("Vault not found, disabling plugin...");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        FileConfiguration config = getConfig();
        saveDefaultMessageConfig();
        messages = getMessageConfig();

        ticketPrice = config.getDouble("ticket-price", 2.5);
        maxTicketCount = config.getInt("max-ticket-count", 100);
        chanceWin = config.getDouble("chance-win", 2);
        chancePercent = config.getInt("chance-percent", 20);
        header = ChatColor.translateAlternateColorCodes('&', config.getString("header"));
        footer = ChatColor.translateAlternateColorCodes('&', config.getString("footer"));
        prefix = ChatColor.translateAlternateColorCodes('&', config.getString("prefix"));
        AnnounceInterval = config.getInt("announce-interval", 600);
        DrawInterval = config.getInt("draw-interval", 6000);
        minDonation = config.getDouble("min-donation-amount");
        maxDonation = config.getDouble("max-donation-amount");
        donateCooldown = config.getInt("donate-cooldown");
        minimumChance = config.getInt("minimum-chance");
        maximumChance = config.getInt("maximum-chance");
        startingAmount = config.getDouble("starting-amount", 5000);
        lotteryPot = startingAmount;
        minimumPlayers = config.getInt("minimum-players", 2);
        showHeaderFooter = config.getBoolean("show-header-footer", true);

        logger = Logger.getLogger("Minecraft");
        logger.info("[LotteryPlus] has been enabled!");

        scheduleLotteryDraw();
        scheduleLotteryAnnounce();

        chanceCommandCooldown = new HashMap<>();
        chanceCommandCooldownTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
            @Override
            public void run() {
                chanceCommandCooldown.entrySet().removeIf(entry -> System.currentTimeMillis() - entry.getValue() >= 60000);
            }
        }, 0L, 20L * 60);

        // Set the current time within data.yml to set the 'last draw' as when the plugin is enabled
        dataFile = new File(getDataFolder(), "data.yml");
        if(!dataFile.exists()) {
            try {
                dataFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("Could not create data.yml file: " + e.getMessage());
            }
        }

        setNextDrawTime();
    }

    private FileConfiguration getMessageConfig() {
        File messageConfigFile = new File(getDataFolder(), "messages.yml");
        return YamlConfiguration.loadConfiguration(messageConfigFile);
    }

    private void saveDefaultMessageConfig() {
        File messageConfigFile = new File(getDataFolder(), "messages.yml");
        if (!messageConfigFile.exists()) {
            saveResource("messages.yml", false);
        }
    }

    private void reloadMessageConfig() {
        File messageConfigFile = new File(getDataFolder(), "messages.yml");
        messages = YamlConfiguration.loadConfiguration(messageConfigFile);
    }

    public void onDisable() {
        //save the data.yml file
        FileConfiguration dataConfig = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "data.yml"));
        try {
            dataConfig.save(new File(getDataFolder(), "data.yml"));
        } catch (IOException e) {
            e.printStackTrace();
        }
        //cancel the lottery draw task
        Bukkit.getScheduler().cancelTask(lotteryDrawTaskId);
        Bukkit.getScheduler().cancelTask(lotteryAnnounceTaskId);
        Bukkit.getScheduler().cancelTask(chanceCommandCooldownTaskId);
        //log that the plugin was disabled
        getLogger().info("LotteryPlus has been disabled!");
    }

    public void scheduleLotteryDraw() {
        lotteryDrawTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
            @Override
            public void run() {
                isDrawing = true;
                getLogger().info("Lottery holders size:" + lotteryTicketHolders.size());
                getLogger().info("Minimum players:" + minimumPlayers);

                Set<String> uniquePlayers = new HashSet<>();
                for (Object o : lotteryTicketHolders) {
                    uniquePlayers.add(o.toString());
                }
                int numberOfUniquePlayers = uniquePlayers.size();

                if (numberOfUniquePlayers >= minimumPlayers) {
                    Random random = new Random();
                    Player winner = lotteryTicketHolders.get(random.nextInt(lotteryTicketHolders.size()));
                    lastWinnerName = winner.getName();
                    lastWinnerAmount = lotteryPot;
                    lastDrawTime = String.valueOf(new Date().getTime());
                    saveWinnerData(winner, lotteryPot);

                    balance.setBalance(winner, balance.getBalance(winner) + lotteryPot);
                    if (showHeaderFooter) Bukkit.broadcastMessage(header);
                    Bukkit.broadcastMessage(
                            colorize(prefix + messages.getString("draw.lotteryWon")
                                    .replace("%winner%", winner.getName())
                                    .replace("%jackpot%", formatCurrency(lotteryPot)))
                    );
                    if (showHeaderFooter) Bukkit.broadcastMessage(footer);

                    lotteryTicketHolders.clear();
                    lotteryPot = startingAmount;
                } else {
                    if (showHeaderFooter) Bukkit.broadcastMessage(header);
                    Bukkit.broadcastMessage(
                            colorize(prefix + messages.getString("draw.lotteryNotWon")
                                    .replace("%jackpot%", formatCurrency(lotteryPot)))
                    );
                    if (showHeaderFooter) Bukkit.broadcastMessage(footer);
                }

                setNextDrawTime();

                Bukkit.getScheduler().cancelTask(lotteryAnnounceTaskId);
                scheduleLotteryAnnounce();

                isDrawing = false;
            }
        }, 20 * DrawInterval, 20 * DrawInterval);
    }

    public void scheduleLotteryAnnounce() {
        lotteryAnnounceTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
            public void run() {
                if (!isDrawing) {
                    for(Player player : Bukkit.getOnlinePlayers()) {
                        if (showHeaderFooter) player.sendMessage(header);
                        player.sendMessage(colorize(prefix + messages.getString("status.drawnIn")
                                .replace("%time-until-draw%", getTimeUntilNextDraw()))
                        );
                        player.sendMessage(colorize(prefix + messages.getString("status.currentPot")
                                .replace("%current-jackpot%", formatCurrency(lotteryPot))
                                .replace("%ticket-count%", String.valueOf(lotteryTicketHolders.size())))
                        );
                        if (showHeaderFooter) player.sendMessage(footer);
                    }
                }
            }
        }, 20 * AnnounceInterval, 20 * AnnounceInterval);
    }

    private void saveWinnerData(Player winner, double prize) {
        FileConfiguration data = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "data.yml"));
        data.set("last-winner", winner.getName());
        data.set("last-prize", prize);
        data.set("last-draw-time", new Date().getTime());

        try {
            data.save(new File(getDataFolder(), "data.yml"));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Map<String,Object> getLastDrawData() {
        Map<String,Object> data = new HashMap<>();
        FileConfiguration dataConfig = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "data.yml"));
        data.put("last-winner", dataConfig.getString("last-winner"));
        data.put("last-prize", dataConfig.getDouble("last-prize"));
        data.put("last-draw-time", dataConfig.getLong("last-draw-time"));
        return data;
    }

    private String getTimeUntilNextDraw() {
        long currentTime = System.currentTimeMillis();
        long nextDrawTime = dataConfig.getLong("next-draw-time", 0);
        long timeUntilNextDraw = nextDrawTime - currentTime;

        String NextDrawTime;

        if (timeUntilNextDraw / 1000 < 60) {
            // convert timeUntilNextDraw to minutes
            NextDrawTime = TimeUnit.MILLISECONDS.toSeconds(timeUntilNextDraw) + " " + messages.getString("seconds");
        } else {
            // convert timeUntilNextDraw to minutes
            NextDrawTime = TimeUnit.MILLISECONDS.toMinutes(timeUntilNextDraw) + " " + messages.getString("minutes");
        }

        return NextDrawTime;
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        economy = rsp.getProvider();
        return economy != null;
    }

    public class Balance {
        public double getBalance(Player player) {
            return economy.getBalance(player);
        }

        public boolean setBalance(Player player, double amount) {
            return economy.withdrawPlayer(player, economy.getBalance(player)).transactionSuccess()
                    && economy.depositPlayer(player, amount).transactionSuccess();
        }
    }

    public int getPlayerTicketCount(Player player) {
        int count = 0;
        for (Player p : lotteryTicketHolders) {
            if (p.getName().equals(player.getName())) {
                count++;
            }
        }
        return count;
    }

    public String colorize(String str){
        return str.replace('&', '§');
    }

    public void setNextDrawTime() {
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        Calendar now = Calendar.getInstance();
        Calendar nextDraw = Calendar.getInstance();
        nextDraw.add(Calendar.SECOND, DrawInterval);
        dataConfig.set("next-draw-time", nextDraw.getTimeInMillis());
        dataConfig.set("last-draw-time", now.getTimeInMillis());
        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Could not save data.yml file: " + e.getMessage());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("lottery")) {
            if (args.length == 0){
                sender.sendMessage( colorize("&d=== " +getDescription().getFullName()+ " ===") );
                sender.sendMessage( colorize("&aAuthor: OhSoGamer") );
                sender.sendMessage( colorize("&aVersion: " +  getDescription().getVersion() ) );
                sender.sendMessage( colorize("&aFeatures:") );
                sender.sendMessage( colorize("&a- Chance (Allows a user to chance some of their money)") );
                sender.sendMessage( colorize("&a- Donate (Allows a user to donate some of their money)") );
                sender.sendMessage( "" );
                sender.sendMessage( colorize("&aUse /lottery help for a full list of commands") );
                return true;
            }
            if (args[0].equalsIgnoreCase("help")) {
                sender.sendMessage( colorize("&d=== " +getDescription().getFullName()+ " ===") );
                sender.sendMessage( colorize("&aCommands:") );
                if (sender.hasPermission("lotteryplus.buy")) {
                    sender.sendMessage( colorize(messages.getString("help.buy")) );
                }
                if (sender.hasPermission("lotteryplus.donate")) {
                    sender.sendMessage( colorize(messages.getString("help.donate")) );
                }
                if (sender.hasPermission("lotteryplus.chance")) {
                    sender.sendMessage( colorize(messages.getString("help.chance")) );
                }
                if (sender.hasPermission("lotteryplus.status")) {
                    sender.sendMessage( colorize(messages.getString("help.status")) );
                }
                if (sender.hasPermission("lotteryplus.announce")) {
                    sender.sendMessage( colorize(messages.getString("help.announce")) );
                }
                if (sender.hasPermission("lotteryplus.boost")) {
                    sender.sendMessage( colorize(messages.getString("help.boost")) );
                }
                if (sender.hasPermission("lotteryplus.draw")) {
                    sender.sendMessage( colorize(messages.getString("help.draw")) );
                }
                return true;
            }
            if (args[0].equalsIgnoreCase("buy")) {

                if (!(sender instanceof Player)) {
                    sender.sendMessage( messages.getString("noPermission") );
                    return true;
                }

                Player player = (Player) sender;
                if (args.length != 2) {
                    player.sendMessage( colorize(prefix + messages.getString("help.buy")) );
                    return true;
                }

                int ticketCount;
                try {
                    ticketCount = Integer.parseInt(args[1]);
                } catch (NumberFormatException e) {
                    player.sendMessage( colorize(prefix + messages.getString("buy.invalid-tickets")) );
                    return true;
                }
                if (ticketCount < 1 || ticketCount > maxTicketCount) {
                    player.sendMessage( colorize(prefix + messages.getString("buy.invalid-tickets1")
                            .replace("%max-tickets%", String.valueOf(maxTicketCount))) );
                    return true;
                }

                int currentTicketCount = getPlayerTicketCount(player);

                // If the user already has maximum tickets, prevent them from buying more
                if( currentTicketCount == maxTicketCount ){
                    player.sendMessage( colorize(prefix + messages.getString("buy.already-maxed")
                            .replace("%max-tickets%", String.valueOf(maxTicketCount))) );
                    return true;
                }

                // If the user tries to buy more than 100 tickets, limit them to 100-how many they are trying to buy
                if (currentTicketCount + ticketCount > maxTicketCount) {
                    ticketCount = maxTicketCount - currentTicketCount;
                    player.sendMessage( colorize(prefix + messages.getString("buy.x-more")
                            .replace("%tickets%", String.valueOf(ticketCount))) );
                }

                double totalPrice = ticketCount * ticketPrice;

                if (balance.getBalance(player) < totalPrice) {
                    player.sendMessage( colorize(prefix + messages.getString("buy.too-poor")
                            .replace("%ticket-count%", String.valueOf(ticketCount))
                            .replace("%price%", formatCurrency(totalPrice))) );
                    return true;
                }

                balance.setBalance(player, balance.getBalance(player) - totalPrice);
                lotteryPot += totalPrice;

                for (int i = 0; i < ticketCount; i++) {
                    lotteryTicketHolders.add(player);
                }

                player.sendMessage( colorize(prefix + messages.getString("buy.success")
                        .replace("%tickets%", String.valueOf(ticketCount))
                        .replace("%price%", formatCurrency(totalPrice)))
                );

                if (showHeaderFooter) Bukkit.broadcastMessage(header);
                Bukkit.broadcastMessage( colorize(prefix + messages.getString("buy.broadcast")
                        .replace("%name%", player.getName())
                        .replace("%tickets%", String.valueOf(ticketCount))
                        .replace("%jackpot%", formatCurrency(lotteryPot)))
                );
                if (showHeaderFooter) Bukkit.broadcastMessage(footer);
                return true;

            } else if (args[0].equalsIgnoreCase("donate")) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage( messages.getString("noPermission") );
                    return true;
                }
                Player player = (Player) sender;
                if (args.length != 2) {
                    player.sendMessage( colorize(prefix + messages.getString("help.donate")) );
                    return true;
                }

                UUID playerUUID = player.getUniqueId();
                long currentTime = System.currentTimeMillis();
                if (donateCooldown != 0 && lastDonationTime.containsKey(playerUUID) && currentTime - lastDonationTime.get(playerUUID) < donateCooldown * 1000) {
                    player.sendMessage(prefix + messages.getString("donate.cooldown"));
                    return true;
                }

                double donationAmount;

                try {
                    donationAmount = Double.parseDouble(args[1]);
                } catch (NumberFormatException e) {
                    player.sendMessage( colorize(prefix + messages.getString("donation.invalid")) );
                    return true;
                }
                if (donationAmount < minDonation) {
                    player.sendMessage( colorize(prefix + messages.getString("donate.too-low").replace("%minimum%", formatCurrency(minDonation))) );
                    return true;
                }
                if (donationAmount > maxDonation && maxDonation > 0) {
                    player.sendMessage( colorize(prefix + messages.getString("donate.too-high").replace("%maximum%", formatCurrency(maxDonation))) );
                    return true;
                }
                if (balance.getBalance(player) < donationAmount) {
                    player.sendMessage( colorize(prefix + messages.getString("donate.too-poor").replace("%amount%", formatCurrency(donationAmount))) );
                    return true;
                }
                balance.setBalance(player, balance.getBalance(player) - donationAmount);
                lotteryPot += donationAmount;
                player.sendMessage( colorize(prefix + messages.getString("donate.success").replace("%amount%", formatCurrency(donationAmount))) );
                if (showHeaderFooter) Bukkit.broadcastMessage(header);
                Bukkit.broadcastMessage( colorize(prefix + messages.getString("donate.broadcast")
                        .replace("%name%", player.getName())
                        .replace("%amount%", formatCurrency(donationAmount))
                        .replace("%jackpot%", formatCurrency(lotteryPot)))
                );
                if (showHeaderFooter) Bukkit.broadcastMessage(footer);
                return true;
            } else if (args[0].equalsIgnoreCase("chance")) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage( messages.getString("noPermission") );
                    return true;
                }
                Player player = (Player) sender;

                if (args.length != 2) {
                    player.sendMessage( colorize(prefix + messages.getString("help.chance")) );
                    return true;
                }

                double stakeAmount;
                try {
                    stakeAmount = Double.parseDouble(args[1]);
                } catch (NumberFormatException e) {
                    player.sendMessage( colorize(prefix + messages.getString("chance.invalid")) );
                    return true;
                }

                if (stakeAmount <= minimumChance) {
                    player.sendMessage( colorize(prefix + messages.getString("chance.too-low").replace("%amount%", formatCurrency(minimumChance))) );
                    return true;
                }

                if (stakeAmount > maximumChance && maximumChance > 0) {
                    player.sendMessage( colorize(prefix + messages.getString("chance.too-high").replace("%amount%", formatCurrency(maximumChance))) );
                    return true;
                }

                if (balance.getBalance(player) < stakeAmount) {
                    player.sendMessage( colorize(prefix + messages.getString("chance.too-poor").replace("%amount%", formatCurrency(stakeAmount))) );
                    return true;
                }

                if (chanceCommandCooldown.containsKey(player) && System.currentTimeMillis() - chanceCommandCooldown.get(player) < 60000) {
                    player.sendMessage( colorize(prefix + messages.getString("chance.no-spam")) );
                    return true;
                } else {
                    chanceCommandCooldown.put(player, System.currentTimeMillis());
                }

                balance.setBalance(player, balance.getBalance(player) - stakeAmount);
                Random random = new Random();
                int chance = random.nextInt(100);

                if (chance <= chancePercent) {
                    double winnings = stakeAmount * chanceWin;
                    balance.setBalance(player, balance.getBalance(player) + winnings);
                    player.sendMessage( colorize(prefix + messages.getString("chance.won").replace("%winnings%", formatCurrency(winnings))) );

                    if (showHeaderFooter) Bukkit.broadcastMessage(header);
                    Bukkit.broadcastMessage( colorize(prefix + messages.getString("chance.win-broadcast")
                            .replace("%name%", player.getName())
                            .replace("%amount%", formatCurrency(stakeAmount))
                            .replace("%winnings%", formatCurrency(winnings)))
                    );
                    if (showHeaderFooter) Bukkit.broadcastMessage(footer);
                } else {
                    balance.setBalance(player, balance.getBalance(player) - stakeAmount);
                    lotteryPot += stakeAmount;
                    player.sendMessage( colorize(prefix + messages.getString("chance.no-win")) );

                    if (showHeaderFooter) Bukkit.broadcastMessage(header);
                    Bukkit.broadcastMessage( colorize(prefix + messages.getString("chance.no-win-broadcast")
                            .replace("%name%", formatCurrency(stakeAmount))
                            .replace("%amount%", formatCurrency(stakeAmount))
                            .replace("%jackpot%", formatCurrency(lotteryPot)))
                    );
                    if (showHeaderFooter) Bukkit.broadcastMessage(footer);
                }

                return true;
            } else if (args[0].equalsIgnoreCase("announce")) {

                Player player = (Player) sender;
                if (!player.hasPermission("lotteryplus.announce")) {
                    player.sendMessage( colorize(prefix + messages.getString("noPermission") ) );
                    return true;
                }

                if (!(sender instanceof Player)) {
                    sender.sendMessage(messages.getString("noPermission"));
                    return true;
                }
                if (showHeaderFooter) Bukkit.broadcastMessage(header);
                Bukkit.broadcastMessage(colorize(prefix + messages.getString("status.drawnIn")
                        .replace("%time-until-draw%", getTimeUntilNextDraw()))
                );
                Bukkit.broadcastMessage(colorize(prefix + messages.getString("status.currentPot")
                        .replace("%current-jackpot%", formatCurrency(lotteryPot))
                        .replace("%ticket-count%", String.valueOf(lotteryTicketHolders.size())))
                );
                if (showHeaderFooter) Bukkit.broadcastMessage(footer);
                return true;

            } else if (args[0].equalsIgnoreCase("status")) {

                Player player = (Player) sender;
                if (!player.hasPermission("lotteryplus.status")) {
                    player.sendMessage(colorize(messages.getString("noPermission")));
                    return true;
                }
                if (showHeaderFooter) player.sendMessage(header);
                if( lastWinnerName != "" ){
                    Map<String,Object> data = getLastDrawData();
                    String lastWinner = (String) data.get("last-winner");
                    double lastPrize = (double) data.get("last-prize");

                    player.sendMessage( colorize(prefix + messages.getString("status.last-winner").replace("%player%", lastWinner)) );
                    player.sendMessage( colorize(prefix + messages.getString("status.last-prize").replace("%jackpot%", formatCurrency(lastPrize))) );
                }
                player.sendMessage( colorize(prefix + messages.getString("status.current-tickets").replace("%tickets%", String.valueOf(lotteryTicketHolders.size()))) );
                player.sendMessage( colorize(prefix + messages.getString("status.current-pot").replace("%jackpot%", formatCurrency(lotteryPot))) );
                player.sendMessage( colorize(prefix + messages.getString("status.next-draw").replace("%next-draw-time%", getTimeUntilNextDraw())) );
                if (showHeaderFooter) player.sendMessage(footer);
                return true;

            } else if (args[0].equalsIgnoreCase("boost")) {

                if (sender instanceof Player) {
                    Player player = (Player) sender;
                    if (!player.hasPermission("lotteryplus.boost")) {
                        player.sendMessage(colorize(messages.getString("noPermission")));
                        return true;
                    }

                    double boostAmount;
                    try {
                        boostAmount = Double.parseDouble(args[1]);
                    } catch (NumberFormatException e) {
                        player.sendMessage( colorize(prefix + messages.getString("boost.invalid")) );
                        return true;
                    }
                    if (boostAmount <= 0) {
                        player.sendMessage( colorize(prefix + messages.getString("boost.too-low")) );
                        return true;
                    }
                    lotteryPot += boostAmount;

                    if (args.length == 2) {
                        if (showHeaderFooter) Bukkit.broadcastMessage(header);
                        Bukkit.broadcastMessage( colorize(prefix + messages.getString("boost.broadcast-player")
                                .replace("%player%", player.getName())
                                .replace("%amount%", formatCurrency(boostAmount)))
                        );
                        Bukkit.broadcastMessage( colorize(prefix + messages.getString("boost.broadcast-hint")) );
                        if (showHeaderFooter) Bukkit.broadcastMessage(footer);
                    }
                } else if (sender instanceof ConsoleCommandSender) {
                    ConsoleCommandSender console = (ConsoleCommandSender) sender;
                    double boostAmount;
                    boostAmount = Double.parseDouble(args[1]);
                    if (boostAmount <= 0) {
                        logger.info( messages.getString("boost.too-low") );
                        return true;
                    }
                    lotteryPot += boostAmount;

                    if (args.length == 2) {
                        if (showHeaderFooter) Bukkit.broadcastMessage(header);
                        Bukkit.broadcastMessage( colorize(prefix + messages.getString("boost.success-player")) );
                        Bukkit.broadcastMessage( colorize(prefix + messages.getString("boost.success-pot").replace("%jackpot%", formatCurrency(lotteryPot))) );
                        Bukkit.broadcastMessage( colorize(prefix + messages.getString("boost.broadcast-hint")) );
                        if (showHeaderFooter) Bukkit.broadcastMessage(footer);
                    }
                }
                return true;

            } else if (args[0].equalsIgnoreCase("draw")) {

                if (sender instanceof Player) {
                    Player player = (Player) sender;
                    if (!player.hasPermission("lotteryplus.draw")) {
                        player.sendMessage( colorize(messages.getString("noPermission")) );
                        return true;
                    }
                }

                Set<String> uniquePlayers = new HashSet<>();
                for (Object o : lotteryTicketHolders) {
                    uniquePlayers.add(o.toString());
                }
                int numberOfUniquePlayers = uniquePlayers.size();

                isDrawing = true;

                if (numberOfUniquePlayers >= minimumPlayers) {
                    Random random = new Random();
                    Player winner = lotteryTicketHolders.get(random.nextInt(lotteryTicketHolders.size()));
                    lastWinnerName = winner.getName();
                    balance.setBalance(winner, balance.getBalance(winner) + lotteryPot);
                    saveWinnerData(winner, lotteryPot);
                    lotteryPot = 0.0;

                    if (showHeaderFooter) Bukkit.broadcastMessage(header);
                    Bukkit.broadcastMessage(
                            colorize(prefix + messages.getString("draw.lotteryWon")
                                    .replace("%winner%", winner.getName())
                                    .replace("%jackpot%", formatCurrency(lotteryPot)))
                    );
                    if (showHeaderFooter) Bukkit.broadcastMessage(footer);
                } else {
                    if (showHeaderFooter) Bukkit.broadcastMessage(header);
                    Bukkit.broadcastMessage(
                            colorize(prefix + messages.getString("draw.lotteryNotWon")
                                    .replace("%jackpot%", formatCurrency(lotteryPot)))
                    );
                    if (showHeaderFooter) Bukkit.broadcastMessage(footer);
                }

                lotteryTicketHolders.clear();
                isDrawing = false;

                setNextDrawTime();

                Bukkit.getScheduler().cancelTask(lotteryAnnounceTaskId);
                scheduleLotteryAnnounce();

                Bukkit.getScheduler().cancelTask(lotteryDrawTaskId);
                scheduleLotteryDraw();
                logger.info("[LotteryPlus] Lottery drawn early, restarting drawTask");

                Bukkit.getScheduler().cancelTask(lotteryAnnounceTaskId);
                scheduleLotteryAnnounce();
                logger.info("[LotteryPlus] Lottery drawn early, restarting announceTask");

                return true;

            } else if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission("lottery.reload")) {
                    sender.sendMessage("You do not have permission to use this command");
                    return true;
                }

                reloadConfig();
                FileConfiguration config = getConfig();
                reloadMessageConfig();
                messages = getMessageConfig();

                ticketPrice = config.getDouble("ticket-price", 2.5);
                maxTicketCount = config.getInt("max-ticket-count", 100);
                chanceWin = config.getDouble("chance-win", 2);
                chancePercent = config.getInt("chance-percent", 20);
                header = ChatColor.translateAlternateColorCodes('&', config.getString("header"));
                footer = ChatColor.translateAlternateColorCodes('&', config.getString("footer"));
                prefix = ChatColor.translateAlternateColorCodes('&', config.getString("prefix"));
                AnnounceInterval = config.getInt("announce-interval", 600);
                DrawInterval = config.getInt("draw-interval", 6000);
                minDonation = config.getDouble("min-donation-amount");
                maxDonation = config.getDouble("max-donation-amount");
                donateCooldown = config.getInt("donate-cooldown");
                minimumChance = config.getInt("minimum-chance");
                maximumChance = config.getInt("maximum-chance");
                startingAmount = config.getDouble("starting-amount", 5000);
                minimumPlayers = config.getInt("minimum-players", 2);
                showHeaderFooter = config.getBoolean("show-header-footer", true);

                sender.sendMessage("The lottery plugin has been reloaded");
                return true;
            } else {
                sender.sendMessage( colorize(prefix + messages.getString("lottery.help")) );
                return true;
            }
        }
        return false;
    }
}