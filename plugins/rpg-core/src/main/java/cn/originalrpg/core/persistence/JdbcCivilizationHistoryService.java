package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.CivilizationProject;
import cn.originalrpg.api.model.CivilizationSeason;
import cn.originalrpg.api.service.CivilizationHistoryService;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/** Projects existing durable facts. Reading history never creates honor, rewards or memberships. */
public final class JdbcCivilizationHistoryService implements CivilizationHistoryService {
    private final DataSource dataSource;
    private final Executor executor;

    public JdbcCivilizationHistoryService(DataSource dataSource, Executor executor) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public CompletionStage<List<CivilizationProject>> completedProjects(int page) {
        int offset = offset(page);
        return AsyncJdbc.supply(executor, () -> {
            try (Connection connection = dataSource.getConnection();
                 var query = connection.prepareStatement("""
                         SELECT p.community_id, c.name, c.tag, c.dissolved_at,
                                p.period_key, p.gold_contributed, p.verified_recruits, p.completed_at,
                                (SELECT COUNT(DISTINCT contribution.player_uuid)
                                 FROM rpg_community_contributions contribution
                                 WHERE contribution.community_id = p.community_id
                                   AND contribution.period_key = p.period_key) AS contributors,
                                (SELECT COALESCE(SUM(reward.honor_awarded), 0)
                                 FROM rpg_community_project_rewards reward
                                 WHERE reward.community_id = p.community_id
                                   AND reward.period_key = p.period_key) AS honor_awarded
                         FROM rpg_community_projects p
                         JOIN rpg_communities c ON c.id = p.community_id
                         WHERE p.completed_at IS NOT NULL
                         ORDER BY p.completed_at DESC, p.community_id, p.period_key
                         LIMIT ? OFFSET ?
                         """)) {
                query.setInt(1, PAGE_SIZE);
                query.setInt(2, offset);
                List<CivilizationProject> result = new ArrayList<>();
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        result.add(new CivilizationProject(UUID.fromString(rows.getString("community_id")),
                                rows.getString("name"), rows.getString("tag"), rows.getString("period_key"),
                                rows.getBigDecimal("gold_contributed"), rows.getInt("verified_recruits"),
                                rows.getLong("contributors"), rows.getLong("honor_awarded"),
                                rows.getTimestamp("completed_at").toInstant(), rows.getTimestamp("dissolved_at") != null));
                    }
                }
                connection.commit();
                return List.copyOf(result);
            } catch (SQLException exception) {
                throw new IllegalStateException("Could not read civilization project history", exception);
            }
        });
    }

    @Override
    public CompletionStage<List<CivilizationSeason>> completedSeasons(int page) {
        int offset = offset(page);
        return AsyncJdbc.supply(executor, () -> {
            try (Connection connection = dataSource.getConnection();
                 var query = connection.prepareStatement("""
                         SELECT id, season_id, updated_at FROM rpg_season_settlements
                         WHERE status = 'COMPLETED'
                         ORDER BY updated_at DESC, id LIMIT ? OFFSET ?
                         """)) {
                query.setInt(1, PAGE_SIZE);
                query.setInt(2, offset);
                List<CivilizationSeason> result = new ArrayList<>();
                try (var rows = query.executeQuery()) {
                    while (rows.next()) result.add(new CivilizationSeason(UUID.fromString(rows.getString("id")),
                            rows.getString("season_id"), rows.getTimestamp("updated_at").toInstant()));
                }
                connection.commit();
                return List.copyOf(result);
            } catch (SQLException exception) {
                throw new IllegalStateException("Could not read civilization season history", exception);
            }
        });
    }

    private static int offset(int page) {
        if (page < 1 || page > MAX_PAGE) throw new IllegalArgumentException("History page must be between 1 and " + MAX_PAGE);
        return (page - 1) * PAGE_SIZE;
    }
}
