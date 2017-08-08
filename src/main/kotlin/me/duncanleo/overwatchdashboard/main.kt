package me.duncanleo.overwatchdashboard

import com.squareup.moshi.KotlinJsonAdapterFactory
import com.squareup.moshi.Moshi
import io.reactivex.Observable
import io.reactivex.schedulers.Schedulers
import me.duncanleo.overwatchdashboard.model.*
import me.duncanleo.overwatchdashboard.network.Network
import me.duncanleo.overwatchdashboard.web.StartServer
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SchemaUtils.create
import org.joda.time.DateTime
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.timer

/**
 * Created by duncanleo on 14/6/17.
 */

val data = mutableMapOf<String, Player>()

fun main(args: Array<String>) {
    // Periodically get updated data
    val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory())
            .build()
    val tags = moshi.adapter(Array<Tag>::class.java).fromJson(File("tags.json").readText())
    if (tags == null) {
        print("Could not read tags.json")
        return
    }

    Database.connect("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
    transaction {
        logger.addLogger(StdOutSqlLogger)
        create (Players, PlayersData)
    }

    timer(name = "data-fetcher", initialDelay = 0, period = TimeUnit.HOURS.toMillis(1)) {
        println("[TIMER] Fetching all data")
        Observable.fromArray(*tags)
                .delay(4, TimeUnit.SECONDS)
                .observeOn(Schedulers.newThread())
                .doOnNext {
                    println("[TIMER] Fetching stats for '$it'")
                }
                .flatMap({
                    Network.nodeOWAPIService.getPlayerStats(platform = it.platform, region = it.region, battleTag = it.tag)
                            .toObservable()
                }, { tag, statsRes ->
                    Pair(tag, statsRes)
                })
                .doOnNext {
                    println("[TIMER] Fetching heroes for '${it.first}'")
                }
                .flatMap({
                    Network.nodeOWAPIService.getPlayerProfile(platform = it.first.platform, region = it.first.region, battleTag = it.first.tag)
                            .toObservable()
                }, { (first, second), profileResponse ->
                    transaction {
                        logger.addLogger(StdOutSqlLogger)

                        val dbPlayer = Player.findOrInsert({ Players.battleTag eq first.tag }, {
                            battleTag = first.tag
                            portrait = second.portrait
                            platform = first.platform
                            region = first.region
                        })

                        val topHeroQP = second.stats?.topHeroes?.quickplay?.first()
                        val topHeroComp = second.stats?.topHeroes?.competitive?.first()

                        val mainHeroQP = Hero.findOrInsert({ Heroes.name eq topHeroQP?.name }, {
                            name = topHeroQP?.name ?: "unknown"
                            img = topHeroQP?.img ?: ""
                        })

                        PlayerData.new {
                            player = dbPlayer
                            level = second.level
                            sr = profileResponse.competitive?.rank ?: -1
                            date = DateTime()
                            mainQP = mainHeroQP

                            if (topHeroComp != null) {
                                val mainHeroComp = Hero.findOrInsert({ Heroes.name eq topHeroComp.name }, {
                                    name = topHeroComp.name
                                    img = topHeroComp.img
                                })
                                mainComp = mainHeroComp
                            }
                        }
                    }
                })
                .retryWhen {
                    it.doOnNext {
                        it.printStackTrace()
                        println("[TIMER] Retry.")
                    }.delay(5, TimeUnit.SECONDS)
                }
                .subscribe({ player ->
//                    println("[TIMER] Completed fetching for '${player.battleTag}'")
//                    data[player.battleTag] = player
                }, { error ->
//                    data.remove( /)
                    error.printStackTrace()
                })
    }

    StartServer()
}