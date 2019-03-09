/**
 * Creared by Bartlomiej Jurek on 23/12/2018
 * Problem z okreslaniem przystanku i linii na ktorej sie wsiada i wysiada bo to trzeba okreslic. Pomysl taki:
 * - na start wybierana jest linia, przystanek startowy oraz godzina odjazdu która wyświetlana jest u góry kasownika
 * - po wybraniu tych rzeczy autobus rusza w "trasę", wyświetlany jest aktualny czas (kwant czasu do ustalenia), aktualny przystanek na którym jest w zależności od czasu
 * - w trakcie przejazdu możliwe jest podanie id karty oraz kupno biletu normalnego/ulgowego, sprawdzenie stanu konta na karcie, zasygnalizowanie wyjścia z autobusu
 * - wszystkie komunikaty wyświetlane są na zielonym ekranie id=screen, sygnalizowane są również lampką id=led, kolor zielony oznacza że akcja się powiodła, kolor czerwony oznacza błąd
 * - napis wyświetlany na ekracnie znika po 2 sekundach czasu rzeczywistego, po takim samym czasie lampka led zmienia kolor na czarny.
 */

package sample;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.event.ActionEvent;
import javafx.scene.control.*;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.util.Duration;
import javafx.util.Pair;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.*;

public class Controller {
    public Button BuyDiscountTicket;
    public Button BuyNormalTicketButton;
    public Button CardStatusButton;
    public TextField cardField;
    public Circle led;
    public Label screen;
    public Label dateLabel;
    public Label hourLabel;
    public Button LeaveBus;
    public AnchorPane anchor2;
    public Button startRide;
    public DatePicker datePicker;
    public ChoiceBox hourPicker;
    public ListView linePicker;
    public ChoiceBox routePicker;
    public Label lastBustStop;
    public Label nextBusStop;
    private Calendar calendar;
    private int timeBoost = 10;
    private String url = "jdbc:sqlserver://bd2.database.windows.net:1433;database=bd2;" +
            "user=adminbd2@bd2;password=Bd2Admin;encrypt=true;trustServerCertificate=false;" +
            "hostNameInCertificate=westeurope1-a.control.database.windows.net;loginTimeout=30;";
    private String validatorName = "Kasownik_1";
    private String validatorPassword = "kasowniczek";
    private int validatorId = 0;
    Connection connection = null;
    Statement statement = null;
    PreparedStatement preparedStatement;

    String line;
    int route;
    int course;
    int dayProfile;
    int timeFromStart = 0;
    int nextStopTime;
    Vector <Pair<String, Integer>> stops = new Vector<>();
    Iterator iterator;

    private static final int DOES_NOT_EXIST = -1;
    private static final double REDUCED_TICKET = 1.5;
    private static final double NORMAL_TICKET = 3.0;
    private static final double[] returns = {0.5, 0.35, 0.20};
    private static final int[] frames = {4, 7, 10};

    /**
     * @param actionEvent
     * - Funkcja sprawdza czy id karty ma dobrą długość, następnie sprawdza w bazie czy karta o takim id istnieje i jest aktywna.
     * - Sprawdzamy czy nie ma już aktywnego biletu, jak ma to stosowna informacja o tym
     * - Jeżeli dane się zgadzają sprawdzany jest stan konta właściciela karty (czy ma wystarczająoo środków)
     * - Jeżeli ma wystarczająco środków leci insert do użycie karty i do transakcji, bilet jest aktywny
     */
    public void buyDiscountTicket(ActionEvent actionEvent) throws SQLException {
        buyTicket(REDUCED_TICKET);
    }

    /**
     * @param actionEvent
     * - Funkcja sprawdza czy id karty ma dobrą długość, następnie sprawdza w bazie czy karta o takim id istnieje i jest aktywna.
     * - Sprawdzamy czy nie ma już aktywnego biletu, jak ma to stosowna informacja o tym
     * - Jeżeli dane się zgadzają sprawdzany jest stan konta właściciela karty (czy ma wystarczająoo środków)
     * - Jeżeli ma wystarczająco środków leci insert do użycie karty i do transakcji, bilet jest aktywny
     */
    public void buyNormalTicket(ActionEvent actionEvent) throws SQLException {
        buyTicket(NORMAL_TICKET);
    }

    /**
     * @param actionEvent
     * - sprawdzamy czy karta o takim id istnieje w bazie, czy jest aktywna
     * - sprawdzamy stan konta pasażera będącego właścicielem karty, stan konta wyświetlamy na ekranie
     */
    public void checkCardStatus(ActionEvent actionEvent) throws SQLException {
        String cardSerialNumber = cardField.getText();
        if(!isCardActive(cardSerialNumber))
            return;
        int passengerId = getPassengerIdByCard(cardSerialNumber);
        if(passengerId <= 0)
            return;

        double money = getMoneyAmount(passengerId);
        screenDisplay(true, "You have: " + money + " credits.");
    }

    /**
     * @param actionEvent
     * - sprawdzamy czy karta o takim id istnieje
     * - sprawdzamy czy bilet jest aktywny i został zakupiony w tym samym autobusie (czy przystanki i linia się zgadzają)
     * - jeżeli się wszystko zgadza wyliczamy ile przystanków przejechał pasażer i zwracamy odpowiednią kwotę na jego konto
     */
    public void leaveBus(ActionEvent actionEvent) throws SQLException {
        String cardSerialNumber = cardField.getText();
        //jezeli karta niepoprawna to nic nie rob
        if(!isCardActive(cardSerialNumber))
            return;
        int cardId = getCardId(cardSerialNumber);
        int passengerId = getPassengerIdByCard(cardSerialNumber);
        //jezli pasazer nie istnieje nic nie rob
        if(passengerId==0)
            return;
        //jesli bilet jest nieaktywny nic nie rob
        if(!isTicketActive(cardId))
        {
            screenDisplay(false, "Ticket is not active.");
            return;
        }
        int currentStopId = getCurrentStopId();

        preparedStatement = connection.prepareStatement("select * from [CardUse] as c JOIN [Transaction] as t on c.TransactionId = t.Id where c.CardId = ? order by c.Id DESC;");
        preparedStatement.setInt(1, cardId);
        ResultSet resultSet = preparedStatement.executeQuery();
        resultSet.next();
        int transactionStopId = resultSet.getInt("StopId");
        double transactionValue = -resultSet.getDouble("BalanceChange");
        //CallableStatement callableStatementatement = connection.prepareCall("EXEC dbo.CountStopsOnRouteBetweenTwoStops @routeId=?, @inStopId=?, @outStopId=? WITH RESULT SETS((");
        CallableStatement callableStatement = connection.prepareCall("{call dbo.CountStopsOnRouteBetweenTwoStops(?, ?, ?, ?)}");
        callableStatement.setInt(1, route);
        callableStatement.setInt(2, transactionStopId);
        callableStatement.setInt(3, currentStopId);
        callableStatement.registerOutParameter(4, Types.INTEGER);
        callableStatement.execute();
        int stopsAmount = callableStatement.getInt(4);

        callableStatement.close();

        int index = 0;
        while(stopsAmount > frames[index])
            ++index;

        double moneyToReturn = returns[index];
        if(transactionValue == NORMAL_TICKET)
            moneyToReturn*=2;

        BigDecimal balanceChange = new BigDecimal(moneyToReturn);

        Timestamp timestamp = new Timestamp(calendar.getTimeInMillis());

        callableStatement = connection.prepareCall("{call dbo.UseCardWithTransaction(?, ?, ?, ?, ?, ?, ?, ?)}");
        callableStatement.setTimestamp(1, timestamp);
        callableStatement.setBigDecimal(2, balanceChange);
        callableStatement.setInt(3, passengerId);
        callableStatement.setInt(4, validatorId);
        callableStatement.setInt(5, currentStopId);
        callableStatement.setInt(6, cardId);
        callableStatement.setInt(7, course);
        callableStatement.setInt(8, 3);
        callableStatement.execute();
        callableStatement.close();

        if(isTicketActive(cardId))
            screenDisplay(false, "Error! Please try again.");
        else screenDisplay(true, "Returned " + moneyToReturn + " credits.");
    }

    /**
     *  - Metoda łączy się z bazą danych, wyświetla datę i godzinę (docelowo wybraną przez użytkownika przy starcie kasownika)
     *  - pobierane są linie aby łatwo można było ustalić przejazd
     *  - uruchamiany jest wirtualny zegar znieniający godzinę co pewien kwant czasu
     */
    public void initialize() {

        datePicker.setDisable(true);
        hourPicker.setDisable(true);
        startRide.setDisable(true);
        routePicker.setDisable(true);
        calendar = Calendar.getInstance();
        try {
            Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
            connection = DriverManager.getConnection(url);
            statement = connection.createStatement();
//LOGOWANIE
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            byte[] encodedHash = digest.digest(validatorPassword.getBytes(StandardCharsets.UTF_8));

            ResultSet resultSet = statement.executeQuery("select * from [User] where [Username]='" + validatorName+"'");
            if(!resultSet.next())
            {
                System.out.println("No such user in database.");
                System.exit(0);
            }
            byte[] passwordHash = resultSet.getBytes("PasswordSHA256");
            if(!java.util.Arrays.equals(passwordHash, encodedHash))
            {
                System.out.println("Error while logging in.");
                System.exit(0);
            }
            validatorId = resultSet.getInt(1);
            System.out.println(validatorId);
//POBIREANIE LINII AUTOBUSOWYCH

           resultSet = statement.executeQuery("select [Name] from dbo.Line order by [Name]");
            while(resultSet.next())
                linePicker.getItems().add(resultSet.getString(1));
        }
        catch (SQLException e)
        {
            screenDisplay(false, "Can not reach database.");
            System.out.println(e.getMessage());
            System.exit(0);
        } catch (ClassNotFoundException e) {
            System.out.println("Driver jdbc not found.");
            System.exit(0);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }

        routePicker.valueProperty().addListener((ov, oldValue, newValue) ->
        {
            if(newValue==null)
                return;
            route = Integer.parseInt(newValue.toString());
            datePicker.setDisable(false);
        });

        datePicker.valueProperty().addListener((ov, oldValue, newValue) ->
        {

            try {
                preparedStatement = connection.prepareStatement("select * from dbo.Line where [Name] = ?;");
                preparedStatement.setString(1, line);
                ResultSet resultSet = preparedStatement.executeQuery();
                resultSet.next();
                int id = resultSet.getInt(1);

                LocalDate date = datePicker.getValue();
                int tmp = date.getDayOfMonth();
                String day = "";
                if(tmp < 10) day += "0"+tmp;
                    else day += tmp;

                tmp = date.getMonthValue();
                String month = "";
                if(tmp<10) month += "0"+tmp;
                    else month += tmp;

                String dateString = "" + date.getYear() + "-" + month + "-" + day;

                hourPicker.getItems().clear();
                preparedStatement = connection.prepareStatement("SELECT DepartureTime FROM Departure\n" +
                        "INNER JOIN DayOnRoute ON DayOnRoute.Id = Departure.DayOnRouteId\n" +
                        "WHERE RouteId = ? AND Date = ? ORDER BY DepartureTime");
                preparedStatement.setInt(1, route);
                preparedStatement.setString(2, dateString);
                resultSet = preparedStatement.executeQuery();
                if(!resultSet.next())
                {
                    startRide.setDisable(true);
                    hourPicker.setDisable(true);
                    return;
                }
                else do
                {
                    hourPicker.getItems().add(resultSet.getTime(1));
                }while(resultSet.next());

            } catch (SQLException e) {
                e.printStackTrace();
            }

            hourPicker.setDisable(false);
        });

        hourPicker.valueProperty().addListener((ov, oldValue, newValue)->
        {
            oldValue = oldValue;
            startRide.setDisable(false);
        });
    }

    /**
     * Metoda wyświtla na ektanie wiadomość oraz sygnalizuje status powodzenia zmianą koloru diody led.
     * Wiadomość znika, dioda led zmienia kolor na czarny po 2 sekundach czasu rzeczywistego.
     * @param success czy do wyświetlenia jest błąd czy też komunikat o powodzeniu
     * @param information informacja wyświetlana na ekranie
     */
    private void screenDisplay(Boolean success, String information) {
        screen.setText(information);
        if(success)
            led.setFill(Color.GREEN);
        else
            led.setFill(Color.RED);

        final Timeline timeline = new Timeline(
                new KeyFrame(
                        Duration.millis(2000),
                        event -> {
                            screen.setText("");
                            led.setFill(Color.BLACK);
                        }
                )
        );
        timeline.setCycleCount(1);
        timeline.play();
    }

    /**
     * @param actionEvent
     * @throws SQLException
     * - Metoda pobiera przystanki z danej trasy posortowane w kolejnosci posortowane od najwczesniejszego do najpozniejszego
     * - Pobiera wszystkie dane z formularza i ustala zmienne
     * - startuje animacje zmieniajaca czas w autobusie zgodnie ze zmienna timeBoost oraz zmienia prztstanki zodnie z rozkladem
     */
    public void startRide(ActionEvent actionEvent) throws SQLException {
        //CZESC ODPOWIEDZIALNA ZA IMPORTOWANIE PRZYSTANKOW
        preparedStatement = connection.prepareStatement("select * from dbo.StopOnRoute as sr join dbo.Stop as s on sr.StopId = s.Id where sr.RouteId = ? order by sr.MinutesSinceDeparture;");
        preparedStatement.setInt(1, route);
        ResultSet resultSet = preparedStatement.executeQuery();

        while(resultSet.next())
        {
            Pair<String, Integer> pair = new Pair(resultSet.getString("Name"), resultSet.getInt("MinutesSinceDeparture")*60);
            stops.add(pair);
        }

        iterator = stops.iterator();
        Pair<String, Integer> stop  = (Pair<String, Integer>)iterator.next();

        //CZESC ODPOWIEDZIALNA ZA SYMULACJE
        SimpleDateFormat hour = new SimpleDateFormat("HH:mm:ss");
        SimpleDateFormat date = new SimpleDateFormat("dd-MM-yyyy");

        lastBustStop.setText(stop.getKey());

        if(iterator.hasNext())
        {
            stop = (Pair<String, Integer>)iterator.next();
            nextStopTime = stop.getValue();
            nextBusStop.setText(stop.getKey());
        }

        calendar.set(Calendar.DAY_OF_MONTH, datePicker.getValue().getDayOfMonth());
        calendar.set(Calendar.MONTH, datePicker.getValue().getMonthValue()-1);
        calendar.set(Calendar.YEAR, datePicker.getValue().getYear());

        String time = hourPicker.getValue().toString();
        String[] timeTable = time.split(":");
        calendar.set(Calendar.HOUR_OF_DAY, Integer.parseInt(timeTable[0]));
        calendar.set(Calendar.MINUTE, Integer.parseInt(timeTable[1]));
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);

        dateLabel.setText(date.format(calendar.getTime()));
        hourLabel.setText(hour.format(calendar.getTime()));
        
        final Timeline timeline = new Timeline(
                new KeyFrame(
                        Duration.millis(1000),
                        event -> {
                            calendar.add(Calendar.SECOND, 1*timeBoost);
                            dateLabel.setText(date.format(calendar.getTime()));
                            hourLabel.setText(hour.format(calendar.getTime()));
                            timeFromStart += 1*timeBoost;
                            if(timeFromStart >= nextStopTime)
                            {
                                if(!iterator.hasNext())
                                    System.exit(0);
                                lastBustStop.setText(nextBusStop.getText());
                                Pair<String, Integer > nextStop =  (Pair<String, Integer>)iterator.next();
                                nextStopTime = nextStop.getValue();
                                nextBusStop.setText(nextStop.getKey());
                            }
                        }
                )
        );
        timeline.setCycleCount(Animation.INDEFINITE);
        timeline.play();

        Timestamp timestamp = new Timestamp(calendar.getTimeInMillis());
        preparedStatement = connection.prepareStatement("INSERT INTO [BusCourse] (RouteId, DateTime) VALUES (?, ?) SELECT SCOPE_IDENTITY()");
        preparedStatement.setInt(1, route);
        preparedStatement.setTimestamp(2, timestamp);

        boolean isResultSet = preparedStatement.execute();

        do{
            if(isResultSet)
            {
                resultSet = preparedStatement.getResultSet();
                if(resultSet!=null)
                {
                    resultSet.next();
                    course = resultSet.getInt(1);
                }
            }
            isResultSet = preparedStatement.getMoreResults();
        } while (isResultSet || (preparedStatement.getUpdateCount()!=-1));

        anchor2.setVisible(false);
    }

    /**
     * @param mouseEvent
     * @throws SQLException
     * Metoda pobiera trasy danej linii autobusowej i dodaje je do pickera formularza
     */
    public void getLineName(MouseEvent mouseEvent) throws SQLException {
        if(linePicker.getSelectionModel().getSelectedItem()==null)
            return;
        line = linePicker.getSelectionModel().getSelectedItem().toString();
        preparedStatement = connection.prepareStatement("select * from dbo.Route where [LineId]=?;");
        preparedStatement.setString(1, line);
        ResultSet resultSet = preparedStatement.executeQuery();
        routePicker.getItems().clear();
        while (resultSet.next())
        {
            routePicker.getItems().add(resultSet.getInt(1));
        }
        routePicker.setDisable(false);
    }

    /**
     * @throws SQLException
     * Czynnosci przy zamykaniu aplikacji kasownika takie jak
     * - zamkniecie bazy
     * - dezaktywacja aktywnych biletow???
     */
    public void close() throws SQLException {
        connection.close();
    }

    /**
     * @param cardSerialNumber
     * @return true if card is active
     * - Metoda sprawdza poprawnosc podanego numeru seryjnego karty
     * - sprawdza czy karta istnieje w bazie danych
     * - sprawdza czy karta jest aktywna
     * Wypisyje rowniez stosowne komunikaty
     */
    private Boolean isCardActive(String cardSerialNumber)
    {
        if(cardSerialNumber.length() != 32)
        {
            screenDisplay(false, "Invalid card ID.");
            return false;
        }

        try {
            preparedStatement = connection.prepareStatement("select * from dbo.Card where SerialNumber=?;");
            preparedStatement.setString(1, cardField.getText());
            ResultSet resultSet = preparedStatement.executeQuery();
            if(!resultSet.next())
            {
                screenDisplay(false, "Card does not exist.");
                return false;
            }

            Boolean isActive = resultSet.getBoolean(3);
            if(!isActive)
            {
                screenDisplay(false, "Card is not active.");
                return false;
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }
        return true;
    }

    /**
     * @param cardSerialNumber
     * @return passenger id
     * Metedoa wyszukuje pasazera bedacego wlascicielem karty i zwraca jego id.
     */
    private int getPassengerIdByCard(String cardSerialNumber)
    {
        try {
            preparedStatement = connection.prepareStatement("select [PassengerId] from dbo.Card where SerialNumber=?;");
            preparedStatement.setString(1, cardSerialNumber);
            ResultSet resultSet = preparedStatement.executeQuery();
            resultSet.next();
            int passengerId = resultSet.getInt(1);
            return passengerId;

        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    /**
     * @param cardSerialNumber
     * @return card id
     * Metoda wyszukuje karte w bazie danych i zwraca jej id.
     */
    private int getCardId (String cardSerialNumber)
    {
        try {
            preparedStatement = connection.prepareStatement("select * from dbo.Card where SerialNumber=?;");
            preparedStatement.setString(1, cardSerialNumber);
            ResultSet resultSet = preparedStatement.executeQuery();
            resultSet.next();
            int cardId = resultSet.getInt(1);
            return cardId;

        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    /**
     * @param userID
     * @return account ballance
     * @throws SQLException
     * Metoda wyszukuje pasazera po jego id i zwraca jego stan konta.
     */
    private double getMoneyAmount(int userID) throws SQLException {
        preparedStatement = connection.prepareStatement("select * from dbo.Passenger where [Id]=?;");
        preparedStatement.setInt(1, userID);
        ResultSet resultSet = preparedStatement.executeQuery();
        if(resultSet.next())
            return resultSet.getDouble(4);
        return DOES_NOT_EXIST;
    }

    /**
     * @param cardId
     * @return information is ticket is active
     * @throws SQLException
     * Metoda sprawdza gdzie byla ostatnia transakcja przy uzyciu karty oraz kiedy ona nastapila.
     */
    private Boolean isTicketActive(int cardId) throws SQLException {
        preparedStatement = connection.prepareStatement("select * from [CardUse] as c JOIN [Transaction] as t on c.TransactionId = t.Id where c.CardId = ? order by c.Id DESC;");
        preparedStatement.setInt(1, cardId);
        ResultSet resultSet = preparedStatement.executeQuery();
        if(!resultSet.next())
            return false;
        //Ostatnia aktywnosc na innym kursie, bilet nieaktywny
        int courseId = resultSet.getInt("BusCourseId");
        if(courseId!=course)
            return false;
        //Ostatnia aktywnosc to wyjscie z autobusu, bilet nieaktywny
        int transactionType = resultSet.getInt("TypeId");
        if(transactionType!=2)
            return false;
        return true;
    }

    /**
     * @return current stop id
     * @throws SQLException
     * Metoda znajduje w bazie przystanek o danej nazwie i zwraca jego Id.
     */
    private int getCurrentStopId() throws SQLException {
        int id = DOES_NOT_EXIST;
        preparedStatement = connection.prepareStatement("select * from dbo.Stop where [Name] = ?;");
        preparedStatement.setString(1, lastBustStop.getText());
        ResultSet resultSet = preparedStatement.executeQuery();
        if(resultSet.next())
            id = resultSet.getInt(1);
        return id;
    }

    /**
     * @param cost cost of the ticket choosen by passenger
     * @throws SQLException
     * Metoda sprawdza poprawnosc wprowadzonych danych, stan konta pasazera oraz czy bilet jest juz aktywny.
     * W przypadku aktywnego biletu wyswietlona jest stosowna informacja.
     * W przypadku biletu niekatywnego przy wystarczajacej ilosci srodkow bilet zostaje nabyty.
     */
    private void buyTicket(double cost) throws SQLException {
        String cardSerialNumber = cardField.getText();
        if(!isCardActive(cardSerialNumber))
            return;
        int passengerId = getPassengerIdByCard(cardSerialNumber);
        int cardId = getCardId(cardSerialNumber);
        int currentStopId = getCurrentStopId();

        if(isTicketActive(cardId))
        {
            screenDisplay(false,"Ticket is already activated.");
            return;
        }

        double money = getMoneyAmount(passengerId);
        if(money < REDUCED_TICKET) {
            screenDisplay(false, "Not enough money.");
            return;
        }

        int stopId = getCurrentStopId();

        //Wszystkie warunki spełnione
        ResultSet resultSet;
        Timestamp timestamp = new Timestamp(calendar.getTimeInMillis());
        CallableStatement callableStatement = connection.prepareCall("{call dbo.UseCardWithTransaction(?, ?, ?, ?, ?, ?, ?, ?)}");
        callableStatement.setTimestamp(1, timestamp);
        callableStatement.setBigDecimal(2, new BigDecimal(-cost));
        callableStatement.setInt(3, passengerId);
        callableStatement.setInt(4, validatorId);
        callableStatement.setInt(5, currentStopId);
        callableStatement.setInt(6, cardId);
        callableStatement.setInt(7, course);
        callableStatement.setInt(8, 2);
        callableStatement.execute();
        callableStatement.close();

        if(!isTicketActive(cardId))
            screenDisplay(false, "Error! Please try again.");
        else screenDisplay(true, "Ticket bought.");
    }
}