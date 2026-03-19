# Using a Relational Database for User Management

This guide provides a detailed walkthrough for configuring JSPWiki to use a relational database for storing user and group information, with a specific focus on MySQL.

## 1. Overview

JSPWiki can be configured to use a relational database for user and group management instead of the default XML files. This is highly recommended for production environments as it offers better performance, scalability, and security.

JSPWiki uses JNDI (Java Naming and Directory Interface) to look up the database connection, which means you'll need to configure a JNDI `DataSource` in your application server (e.g., Tomcat).

## 2. Prerequisites

*   A running MySQL or PostgreSQL server.
*   The appropriate JDBC driver for your database.

## 3. Configuration Steps

### Step 1: Add the JDBC Driver

Download the appropriate JDBC driver for your database and place it in the `lib` directory of your Tomcat installation (`$CATALINA_HOME/lib`).

*   **MySQL:** [MySQL Connector/J](https://dev.mysql.com/downloads/connector/j/)
*   **PostgreSQL:** [PostgreSQL JDBC Driver](https://jdbc.postgresql.org/)

### Step 2: Configure the JNDI DataSource in Tomcat

To configure the JNDI `DataSource`, you'll need to add a `<Resource>` element to your Tomcat's `conf/context.xml` file.

#### MySQL Example

```xml
<Context>
  ...
  <Resource name="jdbc/UserDatabase" auth="Container"
            type="javax.sql.DataSource" maxTotal="100" maxIdle="30"
            maxWaitMillis="10000" username="your_username" password="your_password"
            driverClassName="com.mysql.cj.jdbc.Driver"
            url="jdbc:mysql://localhost:3306/jspwiki?useSSL=false"/>

  <Resource name="jdbc/GroupDatabase" auth="Container"
            type="javax.sql.DataSource" maxTotal="100" maxIdle="30"
            maxWaitMillis="10000" username="your_username" password="your_password"
            driverClassName="com.mysql.cj.jdbc.Driver"
            url="jdbc:mysql://localhost:3306/jspwiki?useSSL=false"/>
  ...
</Context>
```

#### PostgreSQL Example

```xml
<Context>
  ...
  <Resource name="jdbc/UserDatabase" auth="Container"
            type="javax.sql.DataSource" maxTotal="100" maxIdle="30"
            maxWaitMillis="10000" username="your_username" password="your_password"
            driverClassName="org.postgresql.Driver"
            url="jdbc:postgresql://localhost:5432/jspwiki"/>

  <Resource name="jdbc/GroupDatabase" auth="Container"
            type="javax.sql.DataSource" maxTotal="100" maxIdle="30"
            maxWaitMillis="10000" username="your_username" password="your_password"
            driverClassName="org.postgresql.Driver"
            url="jdbc:postgresql://localhost:5432/jspwiki"/>
  ...
</Context>
```

**Note:**

*   Replace `your_username` and `your_password` with your database credentials.
*   The `url` parameter should point to your database server and database name.

### Step 3: Configure JSPWiki

Update your `jspwiki-custom.properties` file to use the `JDBCUserDatabase` and `JDBCGroupDatabase`.

```properties
# Use the JDBC user and group databases
jspwiki.userdatabase = com.wikantik.auth.user.JDBCUserDatabase
jspwiki.groupdatabase = com.wikantik.auth.authorize.JDBCGroupDatabase

# JNDI names for the databases
jspwiki.jdbc.user.jndiname = jdbc/UserDatabase
jspwiki.jdbc.group.jndiname = jdbc/GroupDatabase
```

### Step 4: Create the Database Tables

JSPWiki does not automatically create the necessary tables in the database. You'll need to create them manually.

#### MySQL

```sql
CREATE TABLE users (
    uid VARCHAR(255) NOT NULL,
    login_name VARCHAR(255) NOT NULL,
    full_name VARCHAR(255),
    password VARCHAR(255),
    email VARCHAR(255),
    created DATETIME,
    attributes TEXT,
    PRIMARY KEY (uid)
);

CREATE TABLE groups (
    id INT NOT NULL AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL,
    creator VARCHAR(255),
    created DATETIME,
    modifier VARCHAR(255),
    modified DATETIME,
    PRIMARY KEY (id)
);

CREATE TABLE group_members (
    group_id INT NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    FOREIGN KEY (group_id) REFERENCES groups(id),
    FOREIGN KEY (user_id) REFERENCES users(uid)
);
```

#### PostgreSQL

```sql
CREATE TABLE users (
    uid VARCHAR(255) NOT NULL,
    login_name VARCHAR(255) NOT NULL,
    full_name VARCHAR(255),
    password VARCHAR(255),
    email VARCHAR(255),
    created TIMESTAMP,
    attributes TEXT,
    PRIMARY KEY (uid)
);

CREATE TABLE groups (
    id SERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    creator VARCHAR(255),
    created TIMESTAMP,
    modifier VARCHAR(255),
    modified TIMESTAMP
);

CREATE TABLE group_members (
    group_id INT NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    FOREIGN KEY (group_id) REFERENCES groups(id),
    FOREIGN KEY (user_id) REFERENCES users(uid)
);
```

## 4. Differences Between MySQL and PostgreSQL

While the configuration is similar for both databases, there are a few key differences to be aware of:

*   **JDBC Driver:** Use the appropriate JDBC driver for your database.
*   **JDBC URL:** The JDBC URL format is different for each database.
*   **SQL Dialect:** The SQL syntax for creating tables is slightly different (e.g., `AUTO_INCREMENT` in MySQL vs. `SERIAL` in PostgreSQL).

By following these steps, you can successfully configure JSPWiki to use a relational database for user and group management, providing a more robust and scalable solution for your wiki.
