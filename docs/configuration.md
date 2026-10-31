# Configuration

Every file lives in `config/smpeconomy/` and, except `quiz_items.json`, is written on first start. Modules that are off do not create their files, except `prices.json` and `shop.json`, which the core always loads.

`/smpeco reload` applies edits without a restart, with three exceptions. `modules.json` and `quiz_items.json` need a restart, and `commands.json` needs a restart or vanilla `/reload`.

If a file is invalid after an edit, the previous version stays active and the admin status shows the problem. At startup the mod falls back to the copy in `config/smpeconomy/lastgood/`. If there is no usable copy, the server refuses to start instead of running on default prices and limits. Repair the file, or delete it to get the defaults back.

Money values are whole cents, so `100000` means $1,000.00 with the default currency. Job rewards in `jobs.json` may also use fractions of a cent. Colour codes use `§`.

The tables below list the settings with their defaults. `defaultsVersion` and `configVersion` keys are internal; leave them alone.

## Works with

Other mods change a few settings here. The tested mods and known conflicts are in the [README](../README.md#works-with).

| Setting | File | For |
|---|---|---|
| `disabled`, `renamed` | `commands.json` | A command name another mod also uses |
| `tablistWithStyledPlayerList` | `hud.json` | Send our tab list even though Styled Player List is installed |
| `botsEarn` | `jobs.json` | Let Carpet's `/player` bots earn job pay |

Placeholder API placeholders: `%smpeconomy:balance%`, `%smpeconomy:balance_short%`, `%smpeconomy:bank%`, `%smpeconomy:job%`, `%smpeconomy:job_level%`, `%smpeconomy:currency%`.

## commands.json

```json
{
  "disabled": ["pay", "paytoggle"],
  "renamed": { "shop": "market", "ah": "auctions" }
}
```

`disabled` leaves root commands out. `renamed` gives them a new name, which is also how you get a name back from another mod that owns it. An alias such as `bal` or `money` is its own root. GUI buttons keep working. `smpeco` can be renamed but not disabled. Applied on restart or `/reload`.

## modules.json

`true` or `false` per module id. See the module tables in the [README](../README.md). Module ids missing from the file get their default. `economy` is locked on. `market` requires `auction`.

## Content files

| File | Contents |
|---|---|
| `prices.json` | What `/sell` pays per item, in cents |
| `shop.json` | Shop categories and buy prices |
| `quiz_items.json` | Items that trivia questions and the word scramble draw from. Not written on first start: the bundled list applies until you create this file. |
| `chatgames.json` | Words, phrases and riddles for chat games |
| `megachallenge.json` | Riddles and terms for the MEGA challenge |

## core.json

Start money, treasury, time zone, branding, currency and global multipliers. `startBalance` and the `branding`, `currency` and `multipliers` keys can also be changed in game with `/smpeco admin` or `/smpeco config set`. `treasuryStartCapital` is paid in once on first startup. `timezone` takes a zone id such as `Europe/Berlin`, or `system` for the host's zone, and sets when the daily reset happens. Multipliers must be between 0.1 and 10.

| Key | Default |
|---|---|
| `startBalance` | `0` |
| `treasuryStartCapital` | `2500000` |
| `treasuryHourlySpendPercent` | `5.0` |
| `timezone` | `"system"` |
| `branding.chatPrefix` | `"§6SMP §8» "` |
| `branding.sidebarTitle` | `"§6§lSMP Economy"` |
| `currency.symbol` | `"$"` |
| `currency.symbolAfter` | `false` |
| `currency.nameSingular` | `"dollar"` |
| `currency.namePlural` | `"dollars"` |
| `multipliers.sell` | `1.0` |
| `multipliers.jobPay` | `1.0` |

## starter.json

Starter kit for new players. `kit` is normally filled by `/smpeco starterkit capture`. Slots: 0 to 35 inventory (0 to 8 hotbar), 36 feet, 37 legs, 38 chest, 39 head, 40 offhand, -1 anywhere. The start balance itself is `startBalance` in `core.json`.

| Key | Default |
|---|---|
| `giveKit` | `true` |
| `onlyNewPlayers` | `true` |
| `newPlayerMaxMinutes` | `5` |
| `kitVersion` | `1` |
| `welcome` | (written to the file on first start) |
| `kit` | (written to the file on first start) |

## sell.json

Settings for `/sell`. The prices are in `prices.json`.

| Key | Default |
|---|---|
| `confirmSeconds` | `10` |
| `confirmPriceTolerancePercent` | `2.0` |
| `allowCreative` | `false` |
| `summaryHoverLines` | `12` |

## shop_settings.json

Shop behaviour and daily deals. The categories and buy prices are in `shop.json`.

| Key | Default |
|---|---|
| `treasurySharePercent` | `50.0` |
| `sellInShop` | `true` |
| `allowCreativeSell` | `false` |
| `dailyDealCount` | `5` |
| `dailyDealMinPercent` | `10` |
| `dailyDealMaxPercent` | `25` |
| `dailyDealMinBasePrice` | `100` |
| `dailyDealExcluded` | `[]` |
| `announceDailyDeals` | `true` |
| `maxFavorites` | `45` |
| `recipeGuardSellFactor` | `1.68` |
| `recipeGuardModifierFactor` | `1.0` |

## auction.json

Limits and tax for `/ah`.

| Key | Default |
|---|---|
| `maxListings` | `10` |
| `maxPrice` | `100000000000` |
| `durationHours` | `48` |
| `taxPercent` | `3.0` |
| `maxClaims` | `45` |
| `allowCreative` | `false` |
| `maxNpcListings` | `150` |
| `maxNpcDurationHours` | `48` |
| `maxItemBytes` | `65536` |
| `maxLoreLines` | `64` |
| `sellConfirmSeconds` | `10` |
| `sellWarnBelowMarketPercent` | `50.0` |
| `blockedItems` | (written to the file on first start) |

## orders.json

Player buy orders and the NPC order board (`board*`, `bulk*`). `npcConversions` is a list of `from`, `in`, `to`, `out` entries.

| Key | Default |
|---|---|
| `maxOrdersPerPlayer` | `10` |
| `maxAmountPerOrder` | `100000` |
| `deliveryFeePercent` | `3.0` |
| `expiryDays` | `7` |
| `allowCreative` | `false` |
| `npcEnabled` | `true` |
| `boardEnabled` | `true` |
| `boardSize` | `420` |
| `boardDemandMin` | `1.05` |
| `boardDemandMax` | `1.35` |
| `boardSupplyDropPerStack` | `0.02` |
| `boardDemandFloor` | `0.95` |
| `boardRecoveryHalfLifeHours` | `3.0` |
| `boardMaxPressure` | `1.0` |
| `boardDailyBonusPerPlayer` | `150000` |
| `boardMinAmount` | `64` |
| `boardMaxAmount` | `2304` |
| `boardOrderMinValue` | `20000` |
| `boardOrderMaxValue` | `400000` |
| `boardMinHours` | `6` |
| `boardMaxHours` | `36` |
| `boardRefillPerTick` | `6` |
| `boardCooldownMinutes` | `30` |
| `boardCommonItems` | (written to the file on first start) |
| `boardExclude` | `[]` |
| `npcConversions` | (written to the file on first start) |
| `bulkBudgetPercent` | `40.0` |
| `bulkMinAmount` | `16` |
| `maxBulkOrders` | `3` |

## jobs.json

Bundled file. It holds the job definitions (`jobs`: blocks, mobs and fish with `money` in cents and `xp`) plus these settings. Mob factors multiply job pay: 0.25 for spawner mobs, 0 for spawn-egg, bred and summoned mobs and respawned dragons.

| Key | Default |
|---|---|
| `switchCooldownMinutes` | `30` |
| `maxLevel` | `50` |
| `levelBonusPercent` | `2.0` |
| `xpBase` | `50.0` |
| `xpExponent` | `1.35` |
| `payoutIntervalSeconds` | `5` |
| `spawnerMobFactor` | `0.25` |
| `spawnEggMobFactor` | `0.0` |
| `bredMobFactor` | `0.0` |
| `summonedMobFactor` | `0.0` |
| `respawnedDragonFactor` | `0.0` |
| `botsEarn` | `false` |

## tasks.json

Bundled file with the daily task pool (`tasks`) and these settings. The slot and difficulty ids are `easy`, `medium` and `hard`.

| Key | Default |
|---|---|
| `bonusMoney` | `20000` |
| `bonusKeyTier` | `"common"` |
| `bonusKeys` | `1` |
| `countInCreative` | `false` |

## missions.json

Bundled file with the mission pool (`missions`), the tiers (`tiers`: `easy`, `medium`, `hard`, `elite`) and these settings.

Each player's board rotates every `rotationHours`. Mining missions count self-mined natural blocks, and hunting missions count individual kills of mobs that weren't bred, summoned, hatched from a spawn egg or respawned. Exploration targets pay once per player. Delivery missions give no crate keys.

| Key | Default |
|---|---|
| `maxActive` | `3` |
| `rotationHours` | `6` |
| `cancelCooldownMinutes` | `30` |
| `rotationSeed` | `0` |
| `announceElite` | `true` |
| `progressActionbar` | `true` |
| `spawnerKillFactor` | `0.25` |

## missions_elite.json

Bundled file with the daily elite missions (`missions`) and these settings.

| Key | Default |
|---|---|
| `enabled` | `true` |
| `seed` | `0` |
| `keyTier` | `"epic"` |
| `keys` | `1` |
| `announceStart` | `true` |
| `announceComplete` | `true` |

## bank.json

Interest is a percentage per day on balance held at least `holdHours`, paid by the treasury. `maxInterestPerDay` is the cap per player.

| Key | Default |
|---|---|
| `interestEnabled` | `true` |
| `interestPercent` | `1.0` |
| `maxInterestPerDay` | `200000` |
| `holdHours` | `24` |
| `maxTreasuryPercent` | `5.0` |
| `hourlyBudgetSharePercent` | `50.0` |
| `payoutHours` | `6` |

## hud.json

Sidebar and tab list. `lines` switches individual sidebar lines. `address` is an optional server address line.

| Key | Default |
|---|---|
| `sidebar` | `true` |
| `tablist` | `true` |
| `tablistWithStyledPlayerList` | `false` |
| `updateIntervalTicks` | `20` |
| `address` | `""` |
| `marketShockLine` | (written to the file on first start) |
| `auctionFeverLine` | (written to the file on first start) |
| `tabHeader` | `["", "{title}", "§7Welcome, §f{player}§7!", ""]` |
| `tabFooter` | (written to the file on first start) |
| `jobNames` | (written to the file on first start) |
| `lines` | (written to the file on first start) |
| `missionNameMaxLength` | `20` |

## settings.json

Both gameplay-changing player toggles are off server-wide until you enable them here.

| Key | Default |
|---|---|
| `allowMobSpawnToggle` | `false` |
| `allowPhantomToggle` | `false` |

## stats.json

`/stats`, `/playtime` and `/leaderboard`.

| Key | Default |
|---|---|
| `pageSize` | `10` |
| `snapshotSeconds` | `60` |
| `importVanillaOnStart` | `true` |
| `countFormerKills` | `true` |

## holo.json

Leaderboard holograms.

| Key | Default |
|---|---|
| `updateSeconds` | `60` |
| `entries` | `10` |
| `lineWidth` | `300` |
| `background` | `"80000000"` |
| `billboard` | `"center"` |
| `shadow` | `true` |
| `seeThrough` | `false` |
| `fullBright` | `true` |
| `viewRange` | `1.0` |
| `includeBank` | `true` |
| `maxHolos` | `50` |
| `removeRadius` | `5.0` |

## menu.json

The join hint that points players to `/menu`.

| Key | Default |
|---|---|
| `joinHint` | `true` |
| `joinHintDelaySeconds` | `4` |
| `joinHintPlaytimeHours` | `10` |
| `joinHintTimes` | `5` |

## npc.json

`npcs` and `texts` are normally managed with `/smpeco npc`.

| Key | Default |
|---|---|
| `enabled` | `true` |
| `clickCooldownTicks` | `10` |
| `lookRange` | `6.0` |
| `plateBackground` | `"60101018"` |
| `textBackground` | `"80101018"` |
| `plateHeight` | `2.05` |
| `npcSound` | `"minecraft:entity.villager.trade"` |
| `npcs` | `[]` |
| `texts` | `[]` |

## casino.json

Bet limits and house edges for the casino module. `dailyLossLimit` applies per player.

| Key | Default |
|---|---|
| `enabled` | `true` |
| `minBet` | `100` |
| `maxBet` | `2500000` |
| `maxBetTreasuryPercent` | `2.0` |
| `maxWinTreasuryPercent` | `50.0` |
| `dailyLossLimit` | `5000000` |
| `minesHouseEdgePercent` | `4.0` |
| `hiloHouseEdgePercent` | `4.0` |
| `minesMaxMultiplier` | `25.0` |
| `hiloMaxMultiplier` | `25.0` |
| `minesDefaultBombs` | `3` |
| `announceMinProfit` | `250000` |
| `announceMinMultiplier` | `10.0` |
| `announceMinPayoutForMultiplier` | `50000` |
| `offerTimeoutSeconds` | `30` |
| `idleTimeoutMinutes` | `5` |

## casino_crash.json

| Key | Default |
|---|---|
| `enabled` | `true` |
| `houseEdgePercent` | `4.0` |
| `maxMultiplier` | `100.0` |
| `doublingSeconds` | `5.0` |

## casino_dice.json

| Key | Default |
|---|---|
| `enabled` | `true` |
| `edgePercent` | `3.0` |

## casino_horserace.json

| Key | Default |
|---|---|
| `enabled` | `true` |
| `baseHouseEdgePercent` | `4.5` |
| `raceIntervalSeconds` | `120` |
| `bettingWindowSeconds` | `30` |
| `raceAnnounceCooldownMinutes` | `10` |
| `raceAnnouncements` | `true` |

## minigames.json

Chat games, player coinflips, treasure hunts and the MEGA challenge. Word lists are in `chatgames.json` and `megachallenge.json`.

| Key | Default |
|---|---|
| `chatGames.enabled` | `true` |
| `chatGames.minIntervalMinutes` | `10` |
| `chatGames.maxIntervalMinutes` | `10` |
| `chatGames.timeoutSeconds` | `60` |
| `chatGames.minPlayers` | `1` |
| `chatGames.rewardUnscramble` | `15000` |
| `chatGames.rewardMath` | `10000` |
| `chatGames.rewardFastType` | `10000` |
| `chatGames.rewardRiddle` | `20000` |
| `chatGames.keyChance` | `0.05` |
| `chatGames.rewardsFromTreasury` | `false` |
| `chatGames.riddleHintSeconds` | `20` |
| `chatGames.weights` | (written to the file on first start) |
| `coinflip.minBet` | `10000` |
| `coinflip.maxBet` | `1000000000` |
| `coinflip.feePercent` | `2.0` |
| `coinflip.maxOpenPerPlayer` | `3` |
| `coinflip.announceMinBet` | `500000` |
| `coinflip.announceCooldownSeconds` | `60` |
| `coinflip.offerDoubleOrNothing` | `true` |
| `treasure.enabled` | `true` |
| `treasure.minIntervalMinutes` | `120` |
| `treasure.maxIntervalMinutes` | `240` |
| `treasure.nightFromHour` | `1` |
| `treasure.nightToHour` | `8` |
| `treasure.minPlayers` | `1` |
| `treasure.minRadius` | `300` |
| `treasure.maxRadius` | `1500` |
| `treasure.durationMinutes` | `30` |
| `treasure.hint2AfterMinutes` | `5` |
| `treasure.hint3AfterMinutes` | `10` |
| `treasure.coordinateFuzz` | `50` |
| `treasure.minMoney` | `100000` |
| `treasure.maxMoney` | `500000` |
| `treasure.rewardsFromTreasury` | `false` |
| `treasure.itemRolls` | `2` |
| `treasure.commonKeyChance` | `0.35` |
| `treasure.rareKeyChance` | `0.08` |
| `treasure.searchAttempts` | `40` |
| `treasure.loot` | (written to the file on first start) |
| `megaChallenge.enabled` | `true` |
| `megaChallenge.intervalMinutes` | `30` |
| `megaChallenge.minMinutesAfterStart` | `10` |
| `megaChallenge.minPlayers` | `1` |
| `megaChallenge.announceSeconds` | `60` |
| `megaChallenge.reward` | `1000000` |
| `megaChallenge.epicKeyChance` | `0.05` |
| `megaChallenge.rewardsFromTreasury` | `false` |
| `megaChallenge.answerSeconds` | `240` |
| `megaChallenge.minAnswerDelaySeconds` | `12` |
| `megaChallenge.maxAnswerDelaySeconds` | `30` |
| `megaChallenge.guessCooldownSeconds` | `0` |
| `megaChallenge.maxGuesses` | `1` |
| `megaChallenge.maxTextGuesses` | `1` |
| `megaChallenge.minPlaytimeMinutes` | `60` |
| `megaChallenge.onePerIp` | `true` |
| `megaChallenge.hintSeconds` | `45` |
| `megaChallenge.chatGamePauseMinutes` | `5` |
| `megaChallenge.bonusChance` | `0.1` |
| `megaChallenge.bonusReward` | `2000000` |
| `megaChallenge.weights` | (written to the file on first start) |
| `megaChallenge.mathWeights` | (written to the file on first start) |
| `megaChallenge.triviaWeights` | (written to the file on first start) |
| `megaChallenge.races` | (written to the file on first start) |

## events.json

Server events, trader ranks and the lottery (`lotto*`).

Each lottery draw has `lottoTicketsPerDraw` numbered tickets, and a purchase gets random unsold numbers. At the draw, a jackpot number and one number per entry in `lottoPrizes` are drawn. A prize only counts if its number was sold, so 50 of 1000 tickets is a 5 % jackpot chance. An unwon jackpot grows by `lottoJackpotBase` up to `lottoJackpotMax` (0 means no limit) and goes back to the base once won. Extra prizes don't roll over. With no ticket sold the draw is cancelled and the jackpot stays, unless `lottoRolloverWithoutTickets` is on. Tickets stop selling when the treasury can't cover the draw plus `lottoTreasuryReserve`. `lottoFeePercent` is not used.

| Key | Default |
|---|---|
| `randomEvents` | `true` |
| `minIntervalMinutes` | `120` |
| `maxIntervalMinutes` | `240` |
| `minDurationMinutes` | `20` |
| `maxDurationMinutes` | `30` |
| `minPlayersOnline` | `1` |
| `quietFromHour` | `0` |
| `quietToHour` | `0` |
| `marketDayMultiplier` | `1.5` |
| `goldRushMultiplier` | `2.0` |
| `luckyHourMultiplier` | `2.0` |
| `weights` | (written to the file on first start) |
| `marketDayCategories` | (written to the file on first start) |
| `goldRushJobs` | (written to the file on first start) |
| `traderRanks` | (written to the file on first start) |
| `lottoEnabled` | `true` |
| `lottoTicketPrice` | `10000` |
| `lottoMaxTicketsPerPlayer` | `50` |
| `lottoDrawTime` | `"20:00"` |
| `lottoFeePercent` | `10.0` |
| `lottoReminderMinutes` | `10` |
| `lottoTicketsPerDraw` | `1000` |
| `lottoJackpotBase` | `7200000` |
| `lottoPrizes` | (written to the file on first start) |
| `lottoRolloverWithoutTickets` | `false` |
| `lottoJackpotMax` | `0` |
| `lottoTreasuryReserve` | `2500000` |
| `lottoForcedDraws` | `false` |

## clans.json

| Key | Default |
|---|---|
| `creationFee` | `500000` |
| `maxMembers` | `20` |
| `inviteExpiryMinutes` | `10` |
| `teleportDelaySeconds` | `3` |
| `openJoinHomeMinutes` | `30` |
| `defaultColor` | (written to the file on first start) |
| `respectForeignTeams` | `true` |
| `announce` | `true` |

## bounty.json

| Key | Default |
|---|---|
| `feePercent` | `5.0` |
| `minAmount` | `10000` |
| `maxTotalPerTarget` | `0` |
| `announce` | `true` |
| `announceMinAmount` | `10000` |
| `announceCooldownSeconds` | `30` |
| `targetNoticeCooldownSeconds` | `30` |
| `blockSameTeam` | `true` |
| `blockSameIp` | `true` |
| `minKillerPlaytimeMinutes` | `120` |
| `historySize` | `20` |

## daily.json

Login streak rewards. `rewardsCents` holds the seven daily rewards.

| Key | Default |
|---|---|
| `rewardsCents` | `[25000, 50000, 75000, 100000, 150000, 200000, 250000]` |
| `day7KeyTier` | `"rare"` |
| `day7Keys` | `1` |
| `streakBreakHours` | `48` |
| `reminder` | `true` |
| `reminderDelaySeconds` | `3` |

## afk.json

AFK zone. Set the corners with `/afkzone pos1` and `/afkzone pos2`.

| Key | Default |
|---|---|
| `intervalSeconds` | `300` |
| `rewardCents` | `10000` |
| `keyChancePercent` | `5.0` |
| `keyTier` | `"common"` |
| `maxRewardsPerDay` | `24` |
| `maxAccountsPerIp` | `0` |
| `payFromTreasury` | `false` |
| `heightPadding` | `2` |

## market.json

Market values, price drift and NPC buyers and sellers in the auction house.

| Key | Default |
|---|---|
| `marketFactor` | `1.6` |
| `recipeMarkup` | `1.3` |
| `valueOverrides` | (written to the file on first start) |
| `enchantmentValues` | (written to the file on first start) |
| `minDurabilityFactor` | `0.05` |
| `driftMin` | `0.6` |
| `driftMax` | `1.6` |
| `driftHalfLifeHours` | `12.0` |
| `driftStepPerStack` | `0.04` |
| `driftMaxStepPerTrade` | `0.06` |
| `npcBuyersEnabled` | `true` |
| `buyerRandomMin` | `0.85` |
| `buyerRandomMax` | `1.15` |
| `buyerMinDelayMinutes` | `5` |
| `buyerMaxDelayMinutes` | `90` |
| `buyerRetryMinutes` | `15` |
| `buyerMaxRetries` | `4` |
| `buyerPaysEnchantments` | `false` |
| `buyerPaysFixedValues` | `false` |
| `buyerSellerHourShare` | `0.5` |
| `buyerSellerDayShare` | `3.0` |
| `npcSellersEnabled` | `true` |
| `sellerMinOffers` | `50` |
| `sellerMaxOffers` | `80` |
| `offerMinHours` | `6` |
| `offerMaxHours` | `24` |
| `offerPriceMin` | `0.95` |
| `offerPriceMax` | `1.4` |
| `bargainChance` | `0.2` |
| `bargainPriceMin` | `0.4` |
| `bargainPriceMax` | `0.8` |
| `bargainMinDiscount` | `0.1` |
| `bargainHiddenChance` | `0.35` |
| `bargainNote` | (written to the file on first start) |
| `bargainMinHours` | `4` |
| `bargainMaxHours` | `12` |
| `bargainMaxActive` | `12` |
| `bargainSellMargin` | `1.15` |
| `bargainDailyBudget` | `500000` |
| `bargainHypeHeadroom` | `3.0` |
| `refillIntervalSeconds` | `120` |
| `refillFastSeconds` | `5` |
| `targetRerollHours` | `3` |
| `traderNames` | (written to the file on first start) |
| `pool` | (written to the file on first start) |

## news.json

Breaking news: random price events, flash sales, bulk orders and auction fever.

| Key | Default |
|---|---|
| `enabled` | `true` |
| `minIntervalMinutes` | `15` |
| `maxIntervalMinutes` | `25` |
| `maxActive` | `3` |
| `quietFromHour` | `1` |
| `quietToHour` | `8` |
| `minPlayersOnline` | `1` |
| `onlineGraceMinutes` | `3` |
| `crashFactor` | `0.5` |
| `crashMaxFactor` | `0.8` |
| `hypeMinFactor` | `1.25` |
| `hypeMaxFactor` | `2.0` |
| `hypeChance` | `0.5` |
| `hypePreferNonShop` | `0.85` |
| `minShopHypeEffect` | `0.25` |
| `minBaseSellCents` | `10` |
| `minDurationMinutes` | `10` |
| `maxDurationMinutes` | `20` |
| `fadeOutMinutes` | `3` |
| `itemCountWeights` | `[70, 25, 5]` |
| `focusItems` | (written to the file on first start) |
| `focusWeight` | `3.0` |
| `crashMarketSold` | `true` |
| `crashItems` | (written to the file on first start) |
| `hypeItems` | (written to the file on first start) |
| `hypeShopItems` | (written to the file on first start) |
| `families` | (written to the file on first start) |
| `actionChance` | `0.3` |
| `flashSaleWeight` | `1.0` |
| `bulkOrderWeight` | `1.0` |
| `auctionFeverWeight` | `1.0` |
| `flashSaleMinPercent` | `20` |
| `flashSaleMaxPercent` | `40` |
| `flashSaleMinutes` | `15` |
| `flashSaleCategories` | `[]` |
| `flashSaleExcludedItems` | (written to the file on first start) |
| `bulkOrderMinutes` | `20` |
| `bulkOrderMinAmount` | `64` |
| `bulkOrderMaxAmount` | `2048` |
| `bulkOrderBudgetShare` | `0.5` |
| `bulkOrderItems` | (written to the file on first start) |
| `auctionFeverMinutes` | `15` |
| `auctionFeverRequiresMarket` | `true` |
| `tickerSeconds` | `2` |
| `historySize` | `100` |
| `titleOnStart` | `true` |
| `soundOnStart` | `true` |

## crates.json

Bundled file with the crate definitions (`crates`). Each crate has an `id`, `name`, `icon` and weighted `rewards`, which can be money, items or keys. The default crate ids are `common`, `rare` and `epic`.


