package com.app.playerservicejava.repository;
import com.app.playerservicejava.model.Player;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlayerRepository extends JpaRepository<Player, String> {

}
