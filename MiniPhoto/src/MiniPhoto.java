// --- 자바 스윙 관련 UI 컴포넌트 및 유틸리티 임포트 ---
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.filechooser.FileNameExtensionFilter;

// --- 자바 AWT 관련 그래픽, 레이아웃, 이벤트 및 이미지 처리 임포트 ---
import java.awt.*;
import java.awt.event.*;
import java.awt.image.*;

// --- 이미지 입출력 관련 임포트 ---
import javax.imageio.ImageIO;

// --- 파일 입출력 및 직렬화 관련 임포트 ---
import java.io.*;

// --- 유틸리티 관련 임포트 ---
import java.util.Stack;

/**
 * MiniPhoto는 간단한 이미지 편집 기능을 제공하는 자바 스윙 기반의 데스크톱 애플리케이션입니다.
 * 이미지 열기, 저장, 그레이스케일 변환, 자르기, 그리기, 텍스트 삽입, 밝기 조절, 실행 취소 기능을 포함합니다.
 */
public class MiniPhoto extends JFrame {
    @Serial
    private static final long serialVersionUID = 1L;

    // --- 이미지 데이터 ---
    private BufferedImage currentImage; // 현재 작업 중인 이미지
    private BufferedImage originalLoadedImage; // 파일에서 처음 불러온 원본 이미지
    private BufferedImage imageForGrayscaleToggle; // 그레이스케일 토글 시 컬러 상태 임시 저장
    private BufferedImage brightnessBaseImage; // 밝기 조절 기준 이미지

    // --- UI 컴포넌트 ---
    private ImagePanel imagePanel;
    private JLabel statusBar;
    private JScrollPane scrollPane;
    private JCheckBox drawCheckBox;
    private JSlider brightnessSlider;
    private JButton textButton;

    // --- 편집 상태 플래그 ---
    private boolean isCropping = false;
    private Point cropStartPoint;
    private Point cropEndPoint;

    private boolean isDrawing = false;
    private Point drawStartPoint;

    private boolean isInsertingText = false;
    private boolean isDefiningTextBounds = false;
    private Point textBoundsStart;
    private Point textBoundsEnd;

    // --- 실행 취소 ---
    private Stack<BufferedImage> undoStack = new Stack<>();

    /**
     * MiniPhoto 애플리케이션 생성자: UI 초기화 및 이벤트 리스너 설정.
     */
    public MiniPhoto() {
        super("MiniPhoto - 이미지 편집기");

        // --- UI 요소 생성 ---
        JButton openButton = new JButton("열기");
        JButton saveButton = new JButton("저장");
        JButton grayscaleButton = new JButton("흑백/컬러");
        JButton cropButton = new JButton("자르기");
        JButton undoButton = new JButton("실행취소");
        drawCheckBox = new JCheckBox("그리기");
        textButton = new JButton("텍스트");

        brightnessSlider = new JSlider(-100, 100, 0);
        brightnessSlider.setMajorTickSpacing(50);
        brightnessSlider.setMinorTickSpacing(10);
        brightnessSlider.setPaintTicks(true);
        brightnessSlider.setPaintLabels(true);

        statusBar = new JLabel("준비 완료");
        statusBar.setBorder(BorderFactory.createEtchedBorder());

        imagePanel = new ImagePanel();
        scrollPane = new JScrollPane(imagePanel);

        // --- 상단 패널 레이아웃 (GridBagLayout) ---
        JPanel topPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 5, 2, 5);
        gbc.anchor = GridBagConstraints.WEST;

        int gridxCounter = 0;
        gbc.gridx = gridxCounter++; gbc.gridy = 0; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE; topPanel.add(openButton, gbc);
        gbc.gridx = gridxCounter++; topPanel.add(saveButton, gbc);
        gbc.gridx = gridxCounter++; topPanel.add(grayscaleButton, gbc);
        gbc.gridx = gridxCounter++; topPanel.add(cropButton, gbc);
        gbc.gridx = gridxCounter++; topPanel.add(undoButton, gbc);
        gbc.gridx = gridxCounter++; topPanel.add(drawCheckBox, gbc);
        gbc.gridx = gridxCounter++; topPanel.add(textButton, gbc);
        gbc.gridx = gridxCounter++; topPanel.add(new JLabel("밝기:"), gbc);
        gbc.gridx = gridxCounter++; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL; topPanel.add(brightnessSlider, gbc);

        // --- 메인 프레임 레이아웃 ---
        setLayout(new BorderLayout());
        add(topPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(statusBar, BorderLayout.SOUTH);

        // --- 이벤트 리스너 설정 ---
        openButton.addActionListener(e -> openImage());
        saveButton.addActionListener(e -> saveImage());
        grayscaleButton.addActionListener(e -> toggleGrayscale());
        cropButton.addActionListener(e -> startCropMode());
        undoButton.addActionListener(e -> performUndo());
        textButton.addActionListener(e -> startTextInsertionMode());

        drawCheckBox.addActionListener(e -> {
            if (drawCheckBox.isSelected()) {
                isCropping = false;
                isInsertingText = false;
                isDefiningTextBounds = false;
                imagePanel.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
                statusBar.setText("그리기 모드가 활성화되었습니다. 이미지 위에서 드래그하여 그리세요.");
            } else {
                isDrawing = false;
                imagePanel.setCursor(Cursor.getDefaultCursor());
                statusBar.setText("그리기 모드가 비활성화되었습니다.");
            }
        });

        brightnessSlider.addChangeListener(e -> {
            if (currentImage == null || brightnessBaseImage == null) return;

            float factor = brightnessSlider.getValue() / 100f;
            BufferedImage imageToDisplay;

            if (brightnessSlider.getValueIsAdjusting()) { // 슬라이더 드래그 중 (미리보기)
                BufferedImage previewImage = deepCopy(brightnessBaseImage);
                if (previewImage == null) return;
                applyBrightnessEffect(previewImage, factor);
                imageToDisplay = previewImage;
            } else { // 슬라이더 드래그 완료 (실제 적용)
                pushToUndoStack(currentImage);
                currentImage = deepCopy(brightnessBaseImage);
                if (currentImage == null) {
                    if (!undoStack.isEmpty()) undoStack.pop();
                    return;
                }
                applyBrightnessEffect(currentImage, factor);
                brightnessBaseImage = deepCopy(currentImage);
                imageToDisplay = currentImage;
                statusBar.setText("밝기가 조절되었습니다: " + brightnessSlider.getValue());
            }
            imagePanel.setImage(imageToDisplay);
        });

        // --- 이미지 패널 마우스 이벤트 ---
        imagePanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e_mouse) {
                if (currentImage == null) return;

                if (drawCheckBox.isSelected()) {
                    isDrawing = true;
                    isCropping = false;
                    isInsertingText = false;
                    isDefiningTextBounds = false;
                    pushToUndoStack(currentImage);
                    drawStartPoint = imagePanel.convertPanelPointToImagePoint(e_mouse.getPoint());
                } else if (isCropping) {
                    isDrawing = false;
                    isInsertingText = false;
                    isDefiningTextBounds = false;
                    cropStartPoint = imagePanel.convertPanelPointToImagePoint(e_mouse.getPoint());
                    cropEndPoint = cropStartPoint;
                    imagePanel.setCropSelection(new Rectangle(cropStartPoint.x, cropStartPoint.y, 0, 0));
                    imagePanel.repaint();
                } else if (isInsertingText) {
                    isDefiningTextBounds = true;
                    textBoundsStart = imagePanel.convertPanelPointToImagePoint(e_mouse.getPoint());
                    textBoundsEnd = textBoundsStart;
                    imagePanel.setTextSelectionRectangleToDraw(new Rectangle(textBoundsStart.x, textBoundsStart.y, 0, 0));
                    imagePanel.repaint();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e_mouse) {
                if (isDrawing) {
                    isDrawing = false;
                    brightnessBaseImage = deepCopy(currentImage);
                    drawStartPoint = null;
                    statusBar.setText("그리기가 완료되었습니다.");
                    imagePanel.repaint();
                } else if (isCropping && currentImage != null && cropStartPoint != null) {
                    cropEndPoint = imagePanel.convertPanelPointToImagePoint(e_mouse.getPoint());
                    applyCrop();
                } else if (isDefiningTextBounds && textBoundsStart != null) {
                    isDefiningTextBounds = false;
                    isInsertingText = false;
                    imagePanel.setCursor(Cursor.getDefaultCursor());

                    Rectangle definedBounds = imagePanel.getTextSelectionRectangleToDraw();
                    imagePanel.setTextSelectionRectangleToDraw(null);
                    imagePanel.repaint();

                    if (definedBounds != null && definedBounds.width > 0 && definedBounds.height > 0) {
                        insertTextAtPoint(new Point(definedBounds.x, definedBounds.y));
                    } else {
                        insertTextAtPoint(textBoundsStart);
                    }
                    textBoundsStart = null;
                    textBoundsEnd = null;
                }
            }
        });

        imagePanel.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e_mouse) {
                if (currentImage == null) return;

                if (isDrawing && drawStartPoint != null) {
                    Point currentDrawPoint = imagePanel.convertPanelPointToImagePoint(e_mouse.getPoint());
                    Graphics2D g2d = currentImage.createGraphics();
                    g2d.setColor(Color.RED);
                    g2d.setStroke(new BasicStroke(3));	
                    g2d.drawLine(drawStartPoint.x, drawStartPoint.y, currentDrawPoint.x, currentDrawPoint.y);
                    g2d.dispose();
                    drawStartPoint = currentDrawPoint;
                    imagePanel.repaint();
                } else if (isCropping && cropStartPoint != null) {
                    cropEndPoint = imagePanel.convertPanelPointToImagePoint(e_mouse.getPoint());
                    int x_coord = Math.min(cropStartPoint.x, cropEndPoint.x);
                    int y_coord = Math.min(cropStartPoint.y, cropEndPoint.y);
                    int width = Math.abs(cropStartPoint.x - cropEndPoint.x);
                    int height = Math.abs(cropStartPoint.y - cropEndPoint.y);
                    imagePanel.setCropSelection(new Rectangle(x_coord,y_coord,width,height));
                    imagePanel.repaint();
                } else if (isDefiningTextBounds && textBoundsStart != null) {
                    textBoundsEnd = imagePanel.convertPanelPointToImagePoint(e_mouse.getPoint());
                    int x = Math.min(textBoundsStart.x, textBoundsEnd.x);
                    int y = Math.min(textBoundsStart.y, textBoundsEnd.y);
                    int width = Math.abs(textBoundsStart.x - textBoundsEnd.x);
                    int height = Math.abs(textBoundsStart.y - textBoundsEnd.y);
                    imagePanel.setTextSelectionRectangleToDraw(new Rectangle(x, y, width, height));
                    imagePanel.repaint();
                }
            }
        });

        // --- 프레임 기본 설정 ---
        setSize(800, 600);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setVisible(true);

        // 프로그램 시작 시 이미지 패널 크기 확정 후 초기 이미지 리사이즈
        SwingUtilities.invokeLater(() -> {
            if (originalLoadedImage != null) {
                 resizeImageToFitPanel(originalLoadedImage, false);
            }
            if (currentImage != null) {
                brightnessBaseImage = deepCopy(currentImage);
            }
        });
    }

    /**
     * 값을 0-255 범위로 제한 (색상 값 처리용).
     */
    private int clamp(int val) {
        return Math.max(0, Math.min(255, val));
    }

    /**
     * 이미지에 밝기 효과 적용 (RescaleOp 사용).
     * @param image 대상 이미지
     * @param factor 밝기 조절 계수 (-1.0 ~ 1.0)
     */
    private void applyBrightnessEffect(BufferedImage image, float factor) {
        if (image == null) return;
        float scaleFactor = 1.0f + factor;
        float offsetVal = (factor > 0 ? factor * 25f : factor * 50f);

        RescaleOp rescaleOp = new RescaleOp(
            new float[]{scaleFactor, scaleFactor, scaleFactor, 1.0f}, // R,G,B,A 스케일 팩터
            new float[]{offsetVal, offsetVal, offsetVal, 0.0f},      // R,G,B,A 오프셋
            null
        );
        try {
            rescaleOp.filter(image, image); // 원본 이미지에 효과 적용
        } catch (IllegalArgumentException e) {
            // RescaleOp 실패 시 수동 픽셀 조작 (예외 처리)
            System.err.println("RescaleOp 적용 실패, 수동 밝기 조절로 대체: " + e.getMessage());
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    Color c = new Color(image.getRGB(x, y), true);
                    int r = clamp((int) (c.getRed() * scaleFactor + offsetVal));
                    int g = clamp((int) (c.getGreen() * scaleFactor + offsetVal));
                    int b = clamp((int) (c.getBlue() * scaleFactor + offsetVal));
                    image.setRGB(x, y, new Color(r, g, b, c.getAlpha()).getRGB());
                }
            }
        }
    }

    /**
     * 텍스트 삽입 모드 시작.
     */
    private void startTextInsertionMode() {
        if (currentImage == null) {
            statusBar.setText("텍스트를 삽입할 이미지가 없습니다.");
            return;
        }
        isInsertingText = true;
        isCropping = false;
        drawCheckBox.setSelected(false);
        isDrawing = false;
        isDefiningTextBounds = false;
        imagePanel.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        statusBar.setText("텍스트 모드: 이미지 위에서 클릭 또는 드래그하여 텍스트 영역을 지정하세요.");
    }

    /**
     * 지정된 위치에 텍스트 삽입 (대화상자 사용).
     * @param point 텍스트 삽입 좌상단 좌표 (이미지 기준)
     */
    private void insertTextAtPoint(Point point) {
        JTextField textField = new JTextField(20);
        String[] colorNames = {"검정", "빨강", "초록", "파랑", "흰색"};
        Color[] colors = {Color.BLACK, Color.RED, Color.GREEN, Color.BLUE, Color.WHITE};
        JComboBox<String> colorComboBox = new JComboBox<>(colorNames);

        JPanel textDialogPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc_dialog = new GridBagConstraints();
        gbc_dialog.gridx = 0; gbc_dialog.gridy = 0; gbc_dialog.anchor = GridBagConstraints.WEST; gbc_dialog.insets = new Insets(5, 5, 5, 5);
        textDialogPanel.add(new JLabel("텍스트:"), gbc_dialog);
        gbc_dialog.gridx = 1; gbc_dialog.fill = GridBagConstraints.HORIZONTAL;
        textDialogPanel.add(textField, gbc_dialog);
        gbc_dialog.gridx = 0; gbc_dialog.gridy = 1; gbc_dialog.fill = GridBagConstraints.NONE;
        textDialogPanel.add(new JLabel("색상:"), gbc_dialog);
        gbc_dialog.gridx = 1; gbc_dialog.fill = GridBagConstraints.HORIZONTAL;
        textDialogPanel.add(colorComboBox, gbc_dialog);

        int result = JOptionPane.showConfirmDialog(this, textDialogPanel, "텍스트 삽입", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result == JOptionPane.OK_OPTION) {
            String text = textField.getText();
            if (text != null && !text.trim().isEmpty()) {
                pushToUndoStack(currentImage);
                Graphics2D g2d = currentImage.createGraphics();
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                Font textFont = new Font("Arial", Font.BOLD, 24);
                Color textColor = colors[colorComboBox.getSelectedIndex()];

                g2d.setFont(textFont);
                g2d.setColor(textColor);
                g2d.drawString(text, point.x, point.y);
                g2d.dispose();

                imagePanel.setImage(currentImage);
                brightnessBaseImage = deepCopy(currentImage);
                brightnessSlider.setValue(0);
                statusBar.setText("텍스트가 삽입되었습니다.");
            } else {
                statusBar.setText("텍스트 삽입이 취소되었거나 입력된 텍스트가 없습니다.");
            }
        } else {
            statusBar.setText("텍스트 삽입이 취소되었습니다.");
        }
    }

    /**
     * 이미지 파일 열기.
     */
    private void openImage() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileFilter(new FileNameExtensionFilter("이미지 파일", "jpg", "jpeg", "png", "bmp", "gif"));
        fileChooser.setAcceptAllFileFilterUsed(false);

        if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                File selectedFile = fileChooser.getSelectedFile();
                BufferedImage loadedImage = ImageIO.read(selectedFile);
                if (loadedImage == null) {
                    JOptionPane.showMessageDialog(this, "선택한 파일을 이미지로 불러올 수 없습니다.", "불러오기 오류", JOptionPane.ERROR_MESSAGE);
                    statusBar.setText("이미지 불러오기 실패: 유효한 이미지 파일이 아닙니다.");
                    return;
                }
                originalLoadedImage = loadedImage;
                imageForGrayscaleToggle = null;

                resizeImageToFitPanel(originalLoadedImage, false);

                undoStack.clear();
                pushToUndoStack(currentImage);
                brightnessBaseImage = deepCopy(currentImage);
                brightnessSlider.setValue(0);

                statusBar.setText("이미지 불러옴: " + selectedFile.getName());
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "이미지 불러오기 오류: " + ex.getMessage(), "불러오기 오류", JOptionPane.ERROR_MESSAGE);
                statusBar.setText("이미지 불러오기 실패.");
                ex.printStackTrace();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "이미지 불러오는 중 알 수 없는 오류 발생: " + ex.getMessage(), "불러오기 오류", JOptionPane.ERROR_MESSAGE);
                statusBar.setText("이미지 불러오기 실패 (알 수 없는 오류).");
                ex.printStackTrace();
            }
        }
    }

    /**
     * 이미지를 스크롤 패널 뷰포트 크기에 맞게 리사이즈 (비율 유지).
     * @param sourceImage 원본 이미지
     * @param isUndoOrToggle 실행 취소/토글 작업 중 호출 여부 (true면 그레이스케일 토글 백업 업데이트 안함)
     */
    private void resizeImageToFitPanel(BufferedImage sourceImage, boolean isUndoOrToggle) {
        if (sourceImage == null) return;

        if (scrollPane == null || scrollPane.getViewport() == null) {
             currentImage = deepCopy(sourceImage);
             if (imagePanel != null) imagePanel.setImage(currentImage);
             return;
        }

        int panelWidth = scrollPane.getViewport().getWidth();
        int panelHeight = scrollPane.getViewport().getHeight();

        if (panelWidth <= 0 || panelHeight <= 0) {
            currentImage = deepCopy(sourceImage);
            if (imagePanel != null) imagePanel.setImage(currentImage);
            return;
        }

        int imgWidth = sourceImage.getWidth();
        int imgHeight = sourceImage.getHeight();

        if (imgWidth <= 0 || imgHeight <= 0) {
            currentImage = null;
            if(imagePanel != null) imagePanel.setImage(currentImage);
            statusBar.setText("잘못된 크기의 이미지는 표시할 수 없습니다.");
            return;
        }

        double scale = Math.min(1.0, Math.min((double) panelWidth / imgWidth, (double) panelHeight / imgHeight));
        int newWidth = (int) (imgWidth * scale);
        int newHeight = (int) (imgHeight * scale);

        if (newWidth <= 0 || newHeight <= 0) {
             currentImage = deepCopy(sourceImage);
        } else {
            Image scaledImage = sourceImage.getScaledInstance(newWidth, newHeight, Image.SCALE_SMOOTH);
            currentImage = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = currentImage.createGraphics();
            g2d.drawImage(scaledImage, 0, 0, null);
            g2d.dispose();
        }

        if (imagePanel != null) imagePanel.setImage(currentImage);

        if(!isUndoOrToggle) {
             imageForGrayscaleToggle = deepCopy(currentImage);
        }
    }

    /**
     * 이미지 저장 (PNG, JPG 지원).
     */
    private void saveImage() {
        if (currentImage == null) {
            JOptionPane.showMessageDialog(this, "저장할 이미지가 없습니다.", "저장 오류", JOptionPane.WARNING_MESSAGE);
            return;
        }

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileFilter(new FileNameExtensionFilter("PNG 이미지 (*.png)", "png"));
        fileChooser.setFileFilter(new FileNameExtensionFilter("JPEG 이미지 (*.jpg)", "jpg"));
        fileChooser.setAcceptAllFileFilterUsed(false);

        if (fileChooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                File fileToSave = fileChooser.getSelectedFile();
                String filePath = fileToSave.getAbsolutePath();
                String selectedExtension = "png"; // 기본값

                javax.swing.filechooser.FileFilter selectedFilter = fileChooser.getFileFilter();
                if (selectedFilter instanceof FileNameExtensionFilter) {
                    selectedExtension = ((FileNameExtensionFilter) selectedFilter).getExtensions()[0];
                }

                if (!filePath.toLowerCase().endsWith("." + selectedExtension)) {
                    fileToSave = new File(filePath + "." + selectedExtension);
                }

                BufferedImage imageToSaveActual = currentImage;
                // JPG 저장 시 알파 채널 제거 (흰색 배경)
                if ("jpg".equalsIgnoreCase(selectedExtension) || "jpeg".equalsIgnoreCase(selectedExtension)) {
                    if (currentImage.getType() != BufferedImage.TYPE_INT_RGB) {
                        BufferedImage jpgImage = new BufferedImage(currentImage.getWidth(), currentImage.getHeight(), BufferedImage.TYPE_INT_RGB);
                        Graphics2D g2d = jpgImage.createGraphics();
                        g2d.drawImage(currentImage, 0, 0, Color.WHITE, null);
                        g2d.dispose();
                        imageToSaveActual = jpgImage;
                    }
                }

                boolean success = ImageIO.write(imageToSaveActual, selectedExtension, fileToSave);
                if (success) {
                    statusBar.setText("이미지 저장됨: " + fileToSave.getName());
                } else {
                    JOptionPane.showMessageDialog(this,
                        "이미지 저장 실패: 선택한 형식(" + selectedExtension + ")으로 이미지를 저장할 수 없거나, 지원되지 않는 이미지 타입일 수 있습니다.",
                        "저장 오류", JOptionPane.ERROR_MESSAGE);
                    statusBar.setText("이미지 저장 실패: 지원되지 않는 형식 또는 타입.");
                }
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "이미지 저장 오류 (파일 입출력): " + ex.getMessage(), "저장 오류", JOptionPane.ERROR_MESSAGE);
                statusBar.setText("이미지 저장 실패 (파일 입출력 오류).");
                ex.printStackTrace();
            } catch (Exception ex) {
                 JOptionPane.showMessageDialog(this, "이미지 저장 중 알 수 없는 오류 발생: " + ex.getMessage(), "저장 오류", JOptionPane.ERROR_MESSAGE);
                 statusBar.setText("이미지 저장 실패 (알 수 없는 오류).");
                 ex.printStackTrace();
            }
        }
    }

    /**
     * 흑백/컬러 변환 토글.
     */
    private void toggleGrayscale() {
        if (currentImage == null) {
            statusBar.setText("불러온 이미지가 없습니다.");
            return;
        }
        pushToUndoStack(currentImage);

        if (currentImage.getType() == BufferedImage.TYPE_BYTE_GRAY || isEffectivelyGrayscale(currentImage)) {
            // 컬러로 복원
            if (imageForGrayscaleToggle != null) {
                currentImage = deepCopy(imageForGrayscaleToggle);
                statusBar.setText("이미지가 컬러로 복원되었습니다.");
            } else {
                statusBar.setText("원본 컬러 이미지가 없어 토글할 수 없습니다.");
                if(!undoStack.isEmpty()) undoStack.pop();
                return;
            }
        } else { // 흑백으로 변환
            imageForGrayscaleToggle = deepCopy(currentImage); // 컬러 상태 백업

            BufferedImage grayscaleImage = new BufferedImage(
                    currentImage.getWidth(),
                    currentImage.getHeight(),
                    BufferedImage.TYPE_BYTE_GRAY);
            Graphics2D g2d = grayscaleImage.createGraphics();
            g2d.drawImage(currentImage, 0, 0, null);
            g2d.dispose();
            currentImage = grayscaleImage;
            statusBar.setText("흑백 필터가 적용되었습니다.");
        }
        imagePanel.setImage(currentImage);
        brightnessBaseImage = deepCopy(currentImage);
        brightnessSlider.setValue(0);
    }

    /**
     * 이미지가 실질적으로 흑백인지 픽셀 샘플링으로 확인.
     */
    private boolean isEffectivelyGrayscale(BufferedImage img) {
        if (img == null) return false;
        if (img.getType() == BufferedImage.TYPE_BYTE_GRAY) return true;

        for(int i=0; i<Math.min(10, img.getWidth()); i++){
            for(int j=0; j<Math.min(10, img.getHeight()); j++){
                Color c = new Color(img.getRGB(i,j));
                if(c.getRed() != c.getGreen() || c.getGreen() != c.getBlue()) return false;
            }
        }
        return true;
    }

    /**
     * 자르기 모드 시작.
     */
    private void startCropMode() {
        if (currentImage == null) {
            statusBar.setText("자르기 할 이미지가 없습니다.");
            return;
        }
        isCropping = true;
        isDrawing = false;
        drawCheckBox.setSelected(false);
        isInsertingText = false;
        isDefiningTextBounds = false;
        imagePanel.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        statusBar.setText("자르기 모드: 드래그하여 영역을 선택하고, 마우스를 떼면 잘립니다.");
    }

    /**
     * 선택 영역으로 이미지 자르기.
     */
    private void applyCrop() {
        if (currentImage == null || cropStartPoint == null || cropEndPoint == null) {
            isCropping = false;
            if(imagePanel != null) imagePanel.setCursor(Cursor.getDefaultCursor());
            return;
        }

        int x_coord = Math.min(cropStartPoint.x, cropEndPoint.x);
        int y_coord = Math.min(cropStartPoint.y, cropEndPoint.y);
        int width = Math.abs(cropStartPoint.x - cropEndPoint.x);
        int height = Math.abs(cropStartPoint.y - cropEndPoint.y);

        // 이미지 경계 조정
        x_coord = Math.max(0, x_coord);
        y_coord = Math.max(0, y_coord);
        if (x_coord + width > currentImage.getWidth()) width = currentImage.getWidth() - x_coord;
        if (y_coord + height > currentImage.getHeight()) height = currentImage.getHeight() - y_coord;

        if (width > 0 && height > 0) {
            pushToUndoStack(currentImage);
            try {
                currentImage = currentImage.getSubimage(x_coord, y_coord, width, height);
                imageForGrayscaleToggle = deepCopy(currentImage);
                imagePanel.setImage(currentImage);
                brightnessBaseImage = deepCopy(currentImage);
                brightnessSlider.setValue(0);
                statusBar.setText("이미지가 " + width + "x" + height + " 크기로 잘렸습니다.");
            } catch (RasterFormatException e_raster) {
                statusBar.setText("자르기 실패: " + e_raster.getMessage());
                if(!undoStack.isEmpty()) undoStack.pop();
                e_raster.printStackTrace();
            } catch (Exception ex) {
                statusBar.setText("자르기 중 알 수 없는 오류 발생: " + ex.getMessage());
                if(!undoStack.isEmpty()) undoStack.pop();
                ex.printStackTrace();
            }
        } else {
            statusBar.setText("자르기 취소: 유효하지 않은 선택 영역입니다.");
        }
        isCropping = false;
        imagePanel.setCursor(Cursor.getDefaultCursor());
        imagePanel.setCropSelection(null);
        imagePanel.repaint();
        cropStartPoint = null;
        cropEndPoint = null;
    }

    /**
     * 실행 취소.
     */
    private void performUndo() {
        if (undoStack.size() > 1) { // 현재 상태 + 이전 상태
            undoStack.pop(); // 현재 상태 제거
            BufferedImage previousImage = undoStack.peek();
            if (previousImage != null) {
                currentImage = deepCopy(previousImage);
                imagePanel.setImage(currentImage);
                imageForGrayscaleToggle = deepCopy(currentImage);
                brightnessBaseImage = deepCopy(currentImage);
                brightnessSlider.setValue(0);
                statusBar.setText("실행 취소가 수행되었습니다.");
            } else {
                 statusBar.setText("실행 취소 실패: 이전 상태가 null입니다.");
            }
        } else if (undoStack.size() == 1 ) { // 초기 상태만 존재
             BufferedImage initialImage = undoStack.peek();
             if (initialImage != null && currentImage != initialImage) {
                currentImage = deepCopy(initialImage);
                imagePanel.setImage(currentImage);
                imageForGrayscaleToggle = deepCopy(currentImage);
                brightnessBaseImage = deepCopy(currentImage);
                brightnessSlider.setValue(0);
                statusBar.setText("초기 상태로 되돌렸습니다.");
             } else if (initialImage == null){
                statusBar.setText("실행 취소 실패: 초기 상태가 null입니다.");
             } else {
                statusBar.setText("이미 초기 상태입니다.");
             }
        } else { // 스택 비어있음
            statusBar.setText("더 이상 실행 취소할 내용이 없습니다.");
        }
    }

    /**
     * BufferedImage 깊은 복사.
     * @param bi 원본 BufferedImage
     * @return 복사된 BufferedImage, 실패 시 null
     */
    private BufferedImage deepCopy(BufferedImage bi) {
        if (bi == null) return null;
        int type = bi.getType();
        if (type == BufferedImage.TYPE_CUSTOM || type == 0) {
            type = BufferedImage.TYPE_INT_ARGB; // 호환성 및 투명도 지원
        }
        if (bi.getWidth() <= 0 || bi.getHeight() <= 0) {
            System.err.println("이미지 깊은 복사 실패: 원본 이미지 크기가 유효하지 않음: " + bi.getWidth() + "x" + bi.getHeight());
            return null;
        }
        BufferedImage newImage = new BufferedImage(bi.getWidth(), bi.getHeight(), type);
        Graphics2D g = newImage.createGraphics();
        g.drawImage(bi, 0, 0, null);
        g.dispose();
        return newImage;
    }

    /**
     * 실행 취소 스택에 이미지 상태 추가 (깊은 복사).
     */
    private void pushToUndoStack(BufferedImage imageToPush) {
        if (imageToPush != null) {
            BufferedImage copy = deepCopy(imageToPush);
            if (copy != null) {
                undoStack.push(copy);
            } else {
                System.err.println("실행 취소 스택에 추가 실패: deepCopy가 null을 반환했습니다.");
            }
        }
    }

    /**
     * 이미지를 화면에 표시하는 커스텀 JPanel.
     * 자르기/텍스트 선택 영역 표시 기능 포함.
     */
    private class ImagePanel extends JPanel {
        @Serial
        private static final long serialVersionUID = 1L;
        private BufferedImage imageToDisplay;
        private Rectangle cropSelection; // 자르기 선택 영역 (이미지 좌표)
        private Rectangle textSelectionRectangleToDraw; // 텍스트 삽입 영역 (이미지 좌표)

        public ImagePanel() { }

        /**
         * 표시할 이미지를 설정하고 패널 업데이트.
         */
        public void setImage(BufferedImage img) {
            this.imageToDisplay = img;
            if (img != null && img.getWidth() > 0 && img.getHeight() > 0) {
                setPreferredSize(new Dimension(img.getWidth(), img.getHeight()));
            } else {
                setPreferredSize(new Dimension(600, 400)); // 기본 크기
            }
            revalidate();
            repaint();
        }

        public void setCropSelection(Rectangle selection) { this.cropSelection = selection; }
        public void setTextSelectionRectangleToDraw(Rectangle rect) { this.textSelectionRectangleToDraw = rect; }
        public Rectangle getTextSelectionRectangleToDraw() { return this.textSelectionRectangleToDraw; }

        /**
         * 패널 좌표를 이미지 내부 좌표로 변환 (이미지 중앙 정렬 고려).
         */
        public Point convertPanelPointToImagePoint(Point panelPoint) {
            if (imageToDisplay == null || imageToDisplay.getWidth() <= 0 || imageToDisplay.getHeight() <= 0) {
                return panelPoint;
            }
            int imageXOffset = (getWidth() - imageToDisplay.getWidth()) / 2;
            int imageYOffset = (getHeight() - imageToDisplay.getHeight()) / 2;
            int imgRelativeX = panelPoint.x - imageXOffset;
            int imgRelativeY = panelPoint.y - imageYOffset;
            // 이미지 경계 내로 좌표 조정
            imgRelativeX = Math.max(0, Math.min(imgRelativeX, imageToDisplay.getWidth() -1));
            imgRelativeY = Math.max(0, Math.min(imgRelativeY, imageToDisplay.getHeight() -1));
            return new Point(imgRelativeX, imgRelativeY);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);

            if (imageToDisplay != null && imageToDisplay.getWidth() > 0 && imageToDisplay.getHeight() > 0) {
                int x_coord = (getWidth() - imageToDisplay.getWidth()) / 2;
                int y_coord = (getHeight() - imageToDisplay.getHeight()) / 2;
                g.drawImage(imageToDisplay, x_coord, y_coord, this); // 이미지 중앙에 그리기

                // 자르기 선택 영역 표시
                if (isCropping && cropSelection != null && cropSelection.width > 0 && cropSelection.height > 0) {
                    Graphics2D g2d = (Graphics2D) g.create();
                    g2d.setColor(new Color(0, 0, 255, 100)); // 반투명 파란색
                    Rectangle panelCropRect = new Rectangle(
                        cropSelection.x + x_coord, cropSelection.y + y_coord,
                        cropSelection.width, cropSelection.height);
                    g2d.fill(panelCropRect);
                    g2d.setColor(Color.BLUE);
                    g2d.draw(panelCropRect);
                    g2d.dispose();
                }

                // 텍스트 영역 정의 시 테두리 표시
                if (isDefiningTextBounds && textSelectionRectangleToDraw != null && textSelectionRectangleToDraw.width > 0 && textSelectionRectangleToDraw.height > 0) {
                    Graphics2D g2d = (Graphics2D) g.create();
                    g2d.setColor(new Color(255, 0, 0, 100)); // 반투명 빨간색
                     Rectangle panelTextRect = new Rectangle(
                        textSelectionRectangleToDraw.x + x_coord, textSelectionRectangleToDraw.y + y_coord,
                        textSelectionRectangleToDraw.width, textSelectionRectangleToDraw.height);
                    g2d.draw(panelTextRect); // 테두리만
                    g2d.dispose();
                }
            } else { // 이미지 없을 시 안내 메시지
                g.setColor(Color.LIGHT_GRAY);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(Color.BLACK);
                g.drawString("이미지가 없거나 잘못된 이미지입니다.", 20, 20);
            }
        }
    }

    /**
     * 애플리케이션 실행 (main 메서드).
     */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); // 시스템 기본 L&F 적용
            } catch (Exception e) {
                System.err.println("시스템 Look and Feel 설정 실패: " + e.getMessage());
            }
            new MiniPhoto();
        });
    }
}
