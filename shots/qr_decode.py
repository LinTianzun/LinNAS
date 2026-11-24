import cv2
img = cv2.imread(r"E:\linNas\shots\qr-test.png")
print("shape", None if img is None else img.shape)
big = cv2.resize(img, None, fx=2.5, fy=2.5, interpolation=cv2.INTER_NEAREST)
gray = cv2.cvtColor(big, cv2.COLOR_BGR2GRAY)
det = cv2.QRCodeDetector()
txt, pts, _ = det.detectAndDecode(gray)
print("decode:", repr(txt))