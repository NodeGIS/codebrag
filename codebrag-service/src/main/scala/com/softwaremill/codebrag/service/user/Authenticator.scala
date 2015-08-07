package com.softwaremill.codebrag.service.user

import java.util.UUID
import java.util.concurrent.{Executors, TimeUnit}

import com.softwaremill.codebrag.common.EventBus
import com.softwaremill.codebrag.dao.user.UserDAO
import com.softwaremill.codebrag.domain.{Authentication, User, UserToken}
import com.typesafe.scalalogging.slf4j.Logging

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

trait Authenticator {

  val scheduledExecutor = Executors.newSingleThreadScheduledExecutor()

  def userDAO: UserDAO

  def authenticateWithToken(token: String): Option[User] = {
    userDAO.findByToken(token)
      .filter(_.active)
      .filterNot(usedTokenExpired(token))
  }

  private def usedTokenExpired(token: String): (User) => Boolean = {
    _.tokens.find(_.token == token).exists(_.expireDate.isBeforeNow)
  }

  def findByLogin(login: String): Option[User] = {
    userDAO.findByLowerCasedLogin(login).filter(_.active)
  }

  def deleteOldSoonAndCreateNewToken(user: User, token: Option[UserToken]) = {

    val newToken = UserToken(UUID.randomUUID().toString)
    token match {
      case Some(t) =>
        // When many requests are fired with the same token,
        // it's possible that first one replaces token, and the second one hits the
        // server with old token when it's already replaced.
        // That's why we don't delete the old token immediately, but after some time.
        scheduledExecutor.schedule(new Runnable {
          override def run() = {
            val tokensWithoutUsed = user.tokens.diff(Set(t))
            val userWithoutUsedToken = user.copy(tokens = tokensWithoutUsed + newToken)
            userDAO.modifyUser(userWithoutUsedToken)
          }
        },
        5,
        TimeUnit.SECONDS)

      case None => userDAO.modifyUser(user.copy(tokens = user.tokens + newToken))
    }

    newToken
  }

  def removeExpiredTokens(user: User) = Future {
    val userWithoutExpiredTokens = user.copy(tokens = user.tokens.filterNot(_.expireDate.isBeforeNow))
    userDAO.modifyUser(userWithoutExpiredTokens)
    userWithoutExpiredTokens
  }

  def authenticate(login: String, nonEncryptedPassword: String): Option[User]
}

class UserPasswordAuthenticator(val userDAO: UserDAO, eventBus: EventBus) extends Authenticator with Logging {

  def authenticate(login: String, nonEncryptedPassword: String): Option[User] = {
    userDAO.findByLoginOrEmail(login).filter { user =>
        user.active && Authentication.passwordsMatch(nonEncryptedPassword, user.authentication)
    }
  }

}